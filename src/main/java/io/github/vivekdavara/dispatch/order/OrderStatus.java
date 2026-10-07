package io.github.vivekdavara.dispatch.order;

/** Order lifecycle; see the state machine in DESIGN.md. */
public enum OrderStatus {
    PENDING,
    OFFERED,
    ASSIGNED,
    PICKED_UP,
    DELIVERED,
    CANCELLED
}
