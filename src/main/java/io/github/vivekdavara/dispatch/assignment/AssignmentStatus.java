package io.github.vivekdavara.dispatch.assignment;

/**
 * One offer's lifecycle: OFFERED, then ACCEPTED, DECLINED or EXPIRED; an accepted one ends COMPLETED (delivered)
 * or CANCELLED. OFFERED and ACCEPTED are the "live" statuses the partial unique indexes guard.
 */
public enum AssignmentStatus {
    OFFERED,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    COMPLETED,
    CANCELLED
}
