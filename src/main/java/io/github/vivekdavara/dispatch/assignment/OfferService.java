package io.github.vivekdavara.dispatch.assignment;

import io.github.vivekdavara.dispatch.courier.CourierRepository;
import io.github.vivekdavara.dispatch.courier.CourierStatus;
import io.github.vivekdavara.dispatch.order.OrderStatus;
import io.github.vivekdavara.dispatch.web.ApiErrors;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
 * </ul>
 */
@Service
public class OfferService {

    private final AssignmentRepository assignments;
    private final CourierRepository couriers;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OfferService(AssignmentRepository assignments, CourierRepository couriers, TransactionTemplate tx,
                        Clock clock) {
        this.assignments = assignments;
        this.couriers = couriers;
        this.tx = tx;
        this.clock = clock;
    }

    public Assignment accept(long assignmentId, UUID courierId) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            Assignment a = assignments.transition(assignmentId, courierId, AssignmentStatus.OFFERED,
                            AssignmentStatus.ACCEPTED, now)
                    .orElseThrow(() -> refusal(assignmentId, courierId, "accept"));
            follow(assignments.moveOrder(a.orderId(), OrderStatus.OFFERED, OrderStatus.ASSIGNED, now), a, "order");
            follow(couriers.transition(courierId, CourierStatus.OFFERED, CourierStatus.BUSY, now), a, "courier");
            return a;
        });
    }

    public Assignment decline(long assignmentId, UUID courierId) {
        return release(assignmentId, courierId, AssignmentStatus.DECLINED, clock.instant())
                .orElseThrow(() -> refusal(assignmentId, courierId, "decline"));
    }

    /**
     * Ends a live offer without a delivery (declined or expired): the order goes back to PENDING and the courier
     * to AVAILABLE, with {@code idle_since} reset, so a courier can't keep their place in the fairness tie-break by
     * turning work down. Empty if the offer was no longer OFFERED (someone else answered first).
     */
    Optional<Assignment> release(long assignmentId, UUID courierId, AssignmentStatus outcome, Instant now) {
        return tx.execute(status -> {
            var a = assignments.transition(assignmentId, courierId, AssignmentStatus.OFFERED, outcome, now);
            a.ifPresent(won -> {
                follow(assignments.moveOrder(won.orderId(), OrderStatus.OFFERED, OrderStatus.PENDING, now), won,
                        "order");
                follow(couriers.transition(courierId, CourierStatus.OFFERED, CourierStatus.AVAILABLE, now), won,
                        "courier");
            });
            return a;
        });
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
