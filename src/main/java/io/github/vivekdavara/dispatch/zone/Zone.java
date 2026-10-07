package io.github.vivekdavara.dispatch.zone;

import io.github.vivekdavara.dispatch.domain.GeoPoint;

/** A service area: orders and couriers belong to one zone, and matching never crosses zones. */
public record Zone(String id, String name, GeoPoint center, double radiusKm) {

    /** Whether {@code point} is inside the zone's circle. */
    public boolean contains(GeoPoint point) {
        return center.distanceMetersTo(point) <= radiusKm * 1000;
    }
}
