package io.github.vivekdavara.dispatch.assignment;

import java.time.Instant;
import java.util.UUID;

/** An assignment row: one offer of one order to one courier and what became of it. */
public record Assignment(long id, UUID orderId, UUID courierId, String zoneId, AssignmentStatus status,
                         double distanceMeters, Instant offeredAt, Instant respondedAt) {
}
