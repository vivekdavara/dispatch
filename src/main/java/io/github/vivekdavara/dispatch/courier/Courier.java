package io.github.vivekdavara.dispatch.courier;

import java.time.Instant;
import java.util.UUID;

/** A courier's record in Postgres. {@code idleSince} is when they last became AVAILABLE (null if never). */
public record Courier(UUID id, String zoneId, String name, CourierStatus status, Instant idleSince,
                      Instant createdAt, Instant updatedAt) {
}
