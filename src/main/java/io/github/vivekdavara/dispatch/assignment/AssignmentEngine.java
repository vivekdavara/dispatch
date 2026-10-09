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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Matches a zone's pending orders to couriers, following the rule in DESIGN.md.
 *
 * <p>One pass ({@link #dispatchZone}): take the zone's pending orders highest {@link PriorityScore} first; for
 * each, ask Redis for couriers with a fresh ping near the pickup, keep the ones Postgres says are AVAILABLE, rank
 * them with {@link CourierRanking}, and claim the best one that's still free. A claim is one transaction:
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
        this.events = events;
        this.passTimer = Timer.builder("dispatch.pass").description("one engine pass over a zone").register(meters);
        this.passOrders = DistributionSummary.builder("dispatch.pass.orders")
                .description("pending orders one pass looked at").register(meters);
    }

    /** One pass over the zone: offers as many pending orders as there are free couriers in range. */
    public List<Offer> dispatchZone(String zoneId) {
        return passTimer.record(() -> pass(zoneId));
    }

    private List<Offer> pass(String zoneId) {
        Instant now = clock.instant();
        List<AssignmentRepository.PendingOrder> pending = new ArrayList<>(repo.pendingOrders(zoneId, pendingBatch));
        // The SQL already orders by priority; re-sorting with the rule itself keeps PriorityScore the single
        // definition (it also treats orders stamped in the future, by clock skew, as zero wait).
        Comparator<PriorityScore.Candidate> serving = PriorityScore.servingOrder(now);
        pending.sort(Comparator.comparing(o -> new PriorityScore.Candidate(o.id(), o.tier(), o.createdAt()), serving));
        passOrders.record(pending.size());

        List<Offer> offers = new ArrayList<>();
        for (AssignmentRepository.PendingOrder order : pending) {
            tryAssign(zoneId, order, now).ifPresent(offers::add);
        }
        return offers;
    }

    /** Offers one order to the best free courier, if there is one in range. */
    Optional<Offer> tryAssign(String zoneId, AssignmentRepository.PendingOrder order, Instant now) {
        List<CourierLocations.Nearby> nearby = locations.freshWithin(zoneId, order.pickup(), maxPickupMeters);
        if (nearby.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, Instant> idleSince = repo.availableIdleSince(zoneId, order.id(),
                nearby.stream().map(CourierLocations.Nearby::courierId).toList());
        List<CourierRanking.Candidate> candidates = new ArrayList<>();
        for (CourierLocations.Nearby n : nearby) {
            Instant idle = idleSince.get(n.courierId());
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
     */
    private Optional<Offer> claim(UUID orderId, List<CourierRanking.Ranked> ranked) {
        Instant now = clock.instant();
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
