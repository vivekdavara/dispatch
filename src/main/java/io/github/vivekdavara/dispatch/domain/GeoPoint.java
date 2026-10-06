package io.github.vivekdavara.dispatch.domain;

/**
 * A latitude/longitude pair in degrees (WGS84).
 */
public record GeoPoint(double lat, double lng) {

    /** Mean Earth radius in metres, the value the haversine formula is usually quoted with. */
    static final double EARTH_RADIUS_M = 6_371_008.8;

    public GeoPoint {
        if (Double.isNaN(lat) || lat < -90 || lat > 90) {
            throw new IllegalArgumentException("latitude out of range: " + lat);
        }
        if (Double.isNaN(lng) || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("longitude out of range: " + lng);
        }
    }

    /** Great-circle distance to {@code other} in metres (haversine formula). */
    public double distanceMetersTo(GeoPoint other) {
        double phi1 = Math.toRadians(lat);
        double phi2 = Math.toRadians(other.lat);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(other.lng - lng);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
