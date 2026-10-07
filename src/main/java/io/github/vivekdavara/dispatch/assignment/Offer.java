package io.github.vivekdavara.dispatch.assignment;

import java.time.Instant;
import java.util.UUID;

/** One order offered to one courier: the assignment row the engine created. */
public record Offer(long assignmentId, UUID orderId, UUID courierId, double distanceMeters, Instant offeredAt) {
}
