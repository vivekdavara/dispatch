package io.github.vivekdavara.dispatch.assignment;

import io.github.vivekdavara.dispatch.config.DispatchProperties;
import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.CourierRanking;
import io.github.vivekdavara.dispatch.domain.PriorityScore;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Matches a zone's pending orders to couriers, following the rule in DESIGN.md.
 *
 * <p>One pass ({@link #dispatchZone}): take the zone's pending orders highest {@link PriorityScore} first; for
 * each, ask Redis for couriers with a fresh ping near the pickup, keep the ones Postgres says are AVAILABLE (read
 * once per pass, see {@link #snapshotPass}), rank them with {@link CourierRanking}, and claim the best one that's
 * still free. A claim is one transaction:
 * compare-and-set the order PENDING to OFFERED, compare-and-set a courier AVAILABLE to OFFERED (falling back down
 * the ranking if another engine thread got there first), and insert the assignment. Any number of passes can run
 * at once: the compare-and-sets and the partial unique indexes make double assignment impossible.
 *
 * <p>Each committed claim is published as an {@link Offer} event; the courier socket pushes it to the courier.
 *
 * <p>Every pass is timed ({@code dispatch.pass}) and counts the pending orders it looked at
 * ({@code dispatch.pass.orders}); both are on {@code /actuator/metrics}, and the simulator reads them.
 */
@Service
public class AssignmentEngine {

    private final AssignmentRepository repo;
    private final CourierLocations locations;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final double maxPickupMeters;
    private final int pendingBatch;
    private final boolean candidateSnapshot;
    private final ApplicationEventPublisher events;
    private final Timer passTimer;
    private final DistributionSummary passOrders;

    public AssignmentEngine(AssignmentRepository repo, CourierLocations locations, TransactionTemplate tx,
                            Clock clock, DispatchProperties props, ApplicationEventPublisher events,
                            MeterRegistry meters) {
        this.repo = repo;
        this.locations = locations;
        this.tx = tx;
        this.clock = clock;
        this.maxPickupMeters = props.assignment().maxPickupKm() * 1000;
        this.pendingBatch = props.assignment().pendingBatch();
        this.candidateSnapshot = props.assignment().candidateSnapshot();
        this.events = events;
        this.passTimer = Timer.builder("dispatch.pass").description("one engine pass over a zone").register(meters);
        this.passOrders = DistributionSummary.builder("dispatch.pass.orders")
                .description("pending orders one pass looked at").register(meters);
    }

    /** One pass over the zone: offers as many pending orders as there are free couriers in range. */
    public List<Offer> dispatchZone(String zoneId) {
        return dispatchZone(zoneId, candidateSnapshot);
    }

    /** A pass with either strategy; the tests run both on the same data and compare the offers. */
    List<Offer> dispatchZone(String zoneId, boolean snapshot) {
        return passTimer.record(() -> snapshot ? snapshotPass(zoneId) : perOrderPass(zoneId));
    }

    /**
     * The default pass. One query for the zone's AVAILABLE couriers, one for the pending batch with each order's
     * refusals, then per order only Redis (GEOSEARCH and the freshness MGET); candidates are filtered in memory,
     * and a claimed courier leaves the snapshot. Two shortcuts follow from the snapshot: with no AVAILABLE courier
     * the pass ends after its first query, and once every free courier has been claimed it stops instead of
     * walking the rest of the backlog. The snapshot can go stale during the pass (a courier goes offline, or the
     * manual endpoint runs a pass alongside): that costs a compare-and-set that matches nothing and a fall back
     * down the ranking, never a wrong offer. A courier who becomes free during the pass publishes an event, and
     * the scheduler runs another pass after this one.
     */
    private List<Offer> snapshotPass(String zoneId) {
        Instant now = clock.instant();
        Map<UUID, Instant> available = repo.availableInZone(zoneId);
        if (available.isEmpty()) {
            passOrders.record(0);
            return List.of();
        }
        List<AssignmentRepository.PendingOrder> pending = byPriority(repo.pendingWithRefusals(zoneId, pendingBatch),
                now);
        List<Offer> offers = new ArrayList<>();
        int examined = 0;
        for (AssignmentRepository.PendingOrder order : pending) {
            if (available.isEmpty()) {
                break; // every free courier is taken; the rest of the backlog waits for one to free up
            }
            examined++;
            Optional<Offer> offer = offerToBest(zoneId, order, now, courier -> order.refusedBy().contains(courier)
                    ? null : available.get(courier));
            offer.ifPresent(o -> {
                available.remove(o.courierId());
                offers.add(o);
            });
        }
        passOrders.record(examined);
        return offers;
    }

    /**
     * The original pass (D2), kept behind {@code dispatch.assignment.candidate-snapshot=false} for the before/after
     * measurement and as the reference the snapshot pass is tested against: for every pending order, Redis for
     * nearby couriers and then one Postgres query for which of them are AVAILABLE and haven't refused it.
     */
    private List<Offer> perOrderPass(String zoneId) {
        Instant now = clock.instant();
        List<AssignmentRepository.PendingOrder> pending = byPriority(repo.pendingOrders(zoneId, pendingBatch), now);
        passOrders.record(pending.size());

        List<Offer> offers = new ArrayList<>();
        for (AssignmentRepository.PendingOrder order : pending) {
            offerToBest(zoneId, order, now, null).ifPresent(offers::add);
        }
        return offers;
    }

    /**
     * The SQL already orders by priority; re-sorting with the rule itself keeps PriorityScore the single definition
     * (it also treats orders stamped in the future, by clock skew, as zero wait).
     */
    private static List<AssignmentRepository.PendingOrder> byPriority(List<AssignmentRepository.PendingOrder> batch,
                                                                     Instant now) {
        List<AssignmentRepository.PendingOrder> pending = new ArrayList<>(batch);
        Comparator<PriorityScore.Candidate> serving = PriorityScore.servingOrder(now);
        pending.sort(Comparator.comparing(o -> new PriorityScore.Candidate(o.id(), o.tier(), o.createdAt()), serving));
        return pending;
    }

    /**
     * Offers one order to the best free courier in range, if there is one. {@code idleSince} says which couriers
     * may get this order (non-null: since when they've been idle); null means ask Postgres, as the per-order pass
     * does.
     */
    private Optional<Offer> offerToBest(String zoneId, AssignmentRepository.PendingOrder order, Instant now,
                                        Function<UUID, Instant> idleSince) {
        List<CourierLocations.Nearby> nearby = locations.freshWithin(zoneId, order.pickup(), maxPickupMeters);
        if (nearby.isEmpty()) {
            return Optional.empty();
        }
        if (idleSince == null) {
            idleSince = repo.availableIdleSince(zoneId, order.id(),
                    nearby.stream().map(CourierLocations.Nearby::courierId).toList())::get;
        }
        List<CourierRanking.Candidate> candidates = new ArrayList<>();
        for (CourierLocations.Nearby n : nearby) {
            Instant idle = idleSince.apply(n.courierId());
            if (idle != null) {
                candidates.add(new CourierRanking.Candidate(n.courierId(), n.location(), idle));
            }
        }
        List<CourierRanking.Ranked> ranked = CourierRanking.rank(order.pickup(), candidates, maxPickupMeters, now);
        if (ranked.isEmpty()) {
            return Optional.empty();
        }
        Optional<Offer> offer = claim(order.id(), ranked);
        offer.ifPresent(events::publishEvent); // committed by now, so the courier can answer it at once
        return offer;
    }

    /**
     * Stamped with the time of the claim, not of the pass: a pass can take a while to reach an order, and the
     * offer's 30 s answer window (and the measured assignment latency) must start when the courier gets it.
     * Truncated to microseconds, Postgres's precision, so the Offer event and the stored row carry the same time
     * (Linux clocks have nanoseconds; Postgres would round them).
     */
    private Optional<Offer> claim(UUID orderId, List<CourierRanking.Ranked> ranked) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return tx.execute(status -> {
            if (!repo.markOrderOffered(orderId, now)) {
                return Optional.<Offer>empty(); // another pass took this order
            }
            for (CourierRanking.Ranked r : ranked) {
                UUID courierId = r.candidate().courierId();
                if (repo.markCourierOffered(courierId, now)) {
                    long id = repo.insertOffer(orderId, courierId, r.distanceMeters(), now);
                    return Optional.of(new Offer(id, orderId, courierId, r.distanceMeters(), now));
                }
            }
            status.setRollbackOnly(); // every candidate was taken: the order goes back to PENDING
            return Optional.<Offer>empty();
        });
    }
}
