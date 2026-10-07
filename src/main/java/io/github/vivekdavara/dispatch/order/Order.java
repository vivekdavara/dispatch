package io.github.vivekdavara.dispatch.order;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import java.time.Instant;
import java.util.UUID;

public record Order(
        UUID id,
        String zoneId,
        GeoPoint pickup,
        GeoPoint dropoff,
        OrderTier tier,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
