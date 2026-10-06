package io.github.vivekdavara.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoPointTest {

    @Test
    void distanceToSelfIsZero() {
        GeoPoint p = new GeoPoint(42.3601, -71.0589);
        assertThat(p.distanceMetersTo(p)).isEqualTo(0.0);
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        // Along a meridian the haversine distance is exactly R * dPhi.
        double expected = GeoPoint.EARTH_RADIUS_M * Math.toRadians(1.0);
        double d = new GeoPoint(0, 0).distanceMetersTo(new GeoPoint(1, 0));
        assertThat(d).isCloseTo(expected, within(1e-6));
        assertThat(d).isCloseTo(111_195, within(1.0));
    }

    @Test
    void bostonToNewYorkIsAbout306Km() {
        GeoPoint boston = new GeoPoint(42.3601, -71.0589);
        GeoPoint nyc = new GeoPoint(40.7128, -74.0060);
        assertThat(boston.distanceMetersTo(nyc)).isCloseTo(306_000, within(2_000.0));
    }

    @Test
    void distanceIsSymmetric() {
        GeoPoint a = new GeoPoint(42.35, -71.08);
        GeoPoint b = new GeoPoint(42.37, -71.03);
        assertThat(a.distanceMetersTo(b)).isCloseTo(b.distanceMetersTo(a), within(1e-9));
    }

    @Test
    void antipodalPointsAreHalfTheCircumferenceApart() {
        double d = new GeoPoint(0, 0).distanceMetersTo(new GeoPoint(0, 180));
        assertThat(d).isCloseTo(Math.PI * GeoPoint.EARTH_RADIUS_M, within(1e-3));
    }

    @Test
    void rejectsOutOfRangeCoordinates() {
        assertThatThrownBy(() -> new GeoPoint(90.1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoPoint(0, -180.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoPoint(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
