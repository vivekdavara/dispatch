package io.github.vivekdavara.dispatch.assignment;

/**
 * Something changed in a zone that may let an order be offered: published after the change commits, and turned
 * into an engine pass by {@link DispatchLoop}.
 */
public record DispatchNeeded(String zoneId, Reason reason) {

    public enum Reason {
        /** A new order is waiting. */
        ORDER_CREATED,
        /** A courier went online. */
        COURIER_AVAILABLE,
        /** An offer was declined: the order is waiting again and the courier is free. */
        OFFER_DECLINED,
        /** An offer timed out: the same, for an unanswered offer. */
        OFFER_EXPIRED,
        /** A courier holding an offer lost their socket and didn't come back in time: the order is waiting again. */
        COURIER_DISCONNECTED,
        /** A delivery finished and the courier is free. */
        DELIVERED,
        /** The periodic sweep found pending orders (covers couriers moving into range, which sends no event). */
        SWEEP
    }
}
