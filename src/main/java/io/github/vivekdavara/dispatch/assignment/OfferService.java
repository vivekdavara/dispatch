package io.github.vivekdavara.dispatch.assignment;

import io.github.vivekdavara.dispatch.config.DispatchProperties;
import io.github.vivekdavara.dispatch.courier.CourierRepository;
import io.github.vivekdavara.dispatch.courier.CourierStatus;
import io.github.vivekdavara.dispatch.order.OrderStatus;
import io.github.vivekdavara.dispatch.web.ApiErrors;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A courier's answer to an offer. Each answer is one transaction of three compare-and-sets: the assignment (the
 * one that decides the race: accept, decline and expiry all start from OFFERED, so exactly one of them wins), then
 * the order and the courier, which must follow because nothing else moves an OFFERED order or courier.
 *
 * <ul>
 *   <li>accept: assignment ACCEPTED, order ASSIGNED, courier BUSY.</li>
 *   <li>decline: assignment DECLINED, order back to PENDING, courier back to AVAILABLE. The engine won't offer
 *       that order to that courier again.</li>
 *   <li>expiry ({@link #expireDue}): the same as a decline, for offers nobody answered within the timeout.</li>
 * </ul>
 *
 * After an accept, the same courier reports {@link #pickedUp} (order PICKED_UP) and {@link #delivered}
 * (assignment COMPLETED, order DELIVERED, courier AVAILABLE again).
 *
 * <p>Answers are idempotent per courier: repeating an accept or a decline that already took effect returns the
 * same assignment instead of an error, so courier apps can retry after a dropped connection. A different answer
 * to a settled offer (accepting a declined one, say) is still a 409.
 */
@Service
public class OfferService {

    static final int EXPIRY_BATCH = 500;

    private final AssignmentRepository assignments;
    private final CourierRepository couriers;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration timeout;
    private final ApplicationEventPublisher events;

    public OfferService(AssignmentRepository assignments, CourierRepository couriers, TransactionTemplate tx,
                        Clock clock, DispatchProperties props, ApplicationEventPublisher events) {
        this.assignments = assignments;
        this.couriers = couriers;
        this.tx = tx;
        this.clock = clock;
        this.timeout = props.offers().timeout();
        this.events = events;
    }

    /** When an offer made at {@code offeredAt} stops being answerable. */
    public Instant expiresAt(Instant offeredAt) {
        return offeredAt.plus(timeout);
    }

    /**
     * Accepts the offer. Repeating an accept that already worked returns the same assignment and changes nothing
     * (an app whose connection dropped before the acknowledgement must be able to retry); accepting an offer that
     * ended any other way is a 409.
     */
    public Assignment accept(long assignmentId, UUID courierId) {
        Instant now = clock.instant();
        Optional<Assignment> accepted = tx.execute(status -> {
            var a = assignments.transition(assignmentId, courierId, AssignmentStatus.OFFERED,
                    AssignmentStatus.ACCEPTED, now);
            a.ifPresent(won -> {
                follow(assignments.moveOrder(won.orderId(), OrderStatus.OFFERED, OrderStatus.ASSIGNED, now), won,
                        "order");
                follow(couriers.transition(courierId, CourierStatus.OFFERED, CourierStatus.BUSY, now), won,
                        "courier");
            });
            return a;
        });
        if (accepted.isEmpty()) {
            // Picked up and delivered since still means this accept took effect.
            return repeated(assignmentId, courierId, AssignmentStatus.ACCEPTED, AssignmentStatus.COMPLETED)
                    .orElseThrow(() -> refusal(assignmentId, courierId, "accept"));
        }
        events.publishEvent(new OfferClosed(accepted.get()));
        return accepted.get();
    }

    /** Declines the offer. Like {@link #accept}, repeating a decline that already worked is harmless. */
    public Assignment decline(long assignmentId, UUID courierId) {
        return release(assignmentId, courierId, AssignmentStatus.DECLINED, clock.instant())
                .or(() -> repeated(assignmentId, courierId, AssignmentStatus.DECLINED))
                .orElseThrow(() -> refusal(assignmentId, courierId, "decline"));
    }

    /** This courier's assignment, if it already ended up in one of {@code outcomes}: the answer is a repeat. */
    private Optional<Assignment> repeated(long assignmentId, UUID courierId, AssignmentStatus... outcomes) {
        return assignments.find(assignmentId)
                .filter(a -> a.courierId().equals(courierId) && List.of(outcomes).contains(a.status()));
    }

    /** The courier collected the order: ASSIGNED to PICKED_UP. */
    public Assignment pickedUp(long assignmentId, UUID courierId) {
        Instant now = clock.instant();
        Assignment a = acceptedBy(assignmentId, courierId, "pick up");
        if (!assignments.moveOrder(a.orderId(), OrderStatus.ASSIGNED, OrderStatus.PICKED_UP, now)) {
            throw new ApiErrors.ConflictException("order " + a.orderId() + " was already picked up");
        }
        return a;
    }

    /** The courier handed the order over: the assignment completes and the courier is free for the next one. */
    public Assignment delivered(long assignmentId, UUID courierId) {
        Instant now = clock.instant();
        Assignment done = tx.execute(status -> {
            Assignment a = assignments.transition(assignmentId, courierId, AssignmentStatus.ACCEPTED,
                            AssignmentStatus.COMPLETED, now)
                    .orElseThrow(() -> refusal(assignmentId, courierId, "deliver"));
            if (!assignments.moveOrder(a.orderId(), OrderStatus.PICKED_UP, OrderStatus.DELIVERED, now)) {
                // Rolls the assignment back to ACCEPTED too.
                throw new ApiErrors.ConflictException("order " + a.orderId() + " has to be picked up first");
            }
            follow(couriers.transition(courierId, CourierStatus.BUSY, CourierStatus.AVAILABLE, now), a, "courier");
            return a;
        });
        events.publishEvent(new DispatchNeeded(done.zoneId(), DispatchNeeded.Reason.DELIVERED));
        return done;
    }

    /** This courier's accepted assignment, or the reason it isn't one. */
    private Assignment acceptedBy(long assignmentId, UUID courierId, String verb) {
        return assignments.find(assignmentId)
                .filter(a -> a.courierId().equals(courierId) && a.status() == AssignmentStatus.ACCEPTED)
                .orElseThrow(() -> refusal(assignmentId, courierId, verb));
    }

    /**
     * Expires every offer older than the timeout, in batches, and returns the ones this call expired. An offer
     * answered meanwhile is skipped: the assignment compare-and-set lets exactly one of accept, decline and expiry
     * win.
     */
    public List<Assignment> expireDue() {
        Instant now = clock.instant();
        List<Assignment> expired = new ArrayList<>();
        List<Assignment> due;
        do {
            due = assignments.offeredBefore(now.minus(timeout), EXPIRY_BATCH);
            for (Assignment a : due) {
                release(a.id(), a.courierId(), AssignmentStatus.EXPIRED, now).ifPresent(expired::add);
            }
        } while (due.size() == EXPIRY_BATCH);
        return expired;
    }

    /**
     * Ends a live offer without a delivery (declined or expired): the order goes back to PENDING and the courier
     * to AVAILABLE, with {@code idle_since} reset, so a courier can't keep their place in the fairness tie-break by
     * turning work down. Empty if the offer was no longer OFFERED (someone else answered first).
     */
    Optional<Assignment> release(long assignmentId, UUID courierId, AssignmentStatus outcome, Instant now) {
        Optional<Assignment> released = tx.execute(status -> {
            var a = assignments.transition(assignmentId, courierId, AssignmentStatus.OFFERED, outcome, now);
            a.ifPresent(won -> {
                follow(assignments.moveOrder(won.orderId(), OrderStatus.OFFERED, OrderStatus.PENDING, now), won,
                        "order");
                follow(couriers.transition(courierId, CourierStatus.OFFERED, CourierStatus.AVAILABLE, now), won,
                        "courier");
            });
            return a;
        });
        // After the commit, so the pass this triggers sees the order PENDING and the courier AVAILABLE.
        released.ifPresent(a -> {
            events.publishEvent(new OfferClosed(a));
            events.publishEvent(new DispatchNeeded(a.zoneId(), outcome == AssignmentStatus.EXPIRED
                    ? DispatchNeeded.Reason.OFFER_EXPIRED : DispatchNeeded.Reason.OFFER_DECLINED));
        });
        return released;
    }

    /** Why an answer was refused: 404 if it isn't this courier's offer, 409 if it was already settled. */
    private RuntimeException refusal(long assignmentId, UUID courierId, String verb) {
        return assignments.find(assignmentId)
                .filter(a -> a.courierId().equals(courierId))
                .<RuntimeException>map(a -> new ApiErrors.ConflictException(
                        "can't " + verb + " assignment " + assignmentId + ": it is " + a.status()))
                .orElseGet(() -> new ApiErrors.NotFoundException("assignment " + assignmentId + " for courier "
                        + courierId));
    }

    /** The order and courier moves can't lose a race once the assignment move won; if one does, roll back. */
    private static void follow(boolean moved, Assignment a, String what) {
        if (!moved) {
            throw new IllegalStateException(what + " for assignment " + a.id() + " was not in the expected state");
        }
    }
}
