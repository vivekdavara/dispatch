package io.github.vivekdavara.dispatch.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Against the real Redis; each test uses its own zone key and deletes it afterwards. */
@SpringBootTest
class CourierLocationsTest {

    static final GeoPoint PICKUP = new GeoPoint(42.35, -71.08);

    @Autowired
    CourierLocations locations;

    @Autowired
    StringRedisTemplate redis;

    final String zone = "geo-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        redis.delete(CourierLocations.geoKey(zone));
    }

    /** A point {@code meters} north of the pickup (one degree of latitude is about 111.2 km). */
    static GeoPoint north(double meters) {
        return new GeoPoint(PICKUP.lat() + meters / 111_195.0, PICKUP.lng());
    }

    @Test
    void returnsCouriersNearestFirstWithPositions() {
        UUID far = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        locations.update(zone, far, north(800));
        locations.update(zone, near, north(200));

        var found = locations.freshWithin(zone, PICKUP, 5_000);

        assertThat(found).extracting(CourierLocations.Nearby::courierId).containsExactly(near, far);
        // Redis stores positions as 52-bit geohashes: accurate to well under a metre.
        assertThat(found.get(0).location().distanceMetersTo(north(200))).isLessThan(1.0);
    }

    @Test
    void excludesCouriersOutsideTheRadius() {
        UUID in = UUID.randomUUID();
        locations.update(zone, in, north(900));
        locations.update(zone, UUID.randomUUID(), north(1_100));

        assertThat(locations.freshWithin(zone, PICKUP, 1_000)).extracting(CourierLocations.Nearby::courierId)
                .containsExactly(in);
    }

    @Test
    void aNewPingReplacesTheOldPosition() {
        UUID c = UUID.randomUUID();
        locations.update(zone, c, north(3_000));
        locations.update(zone, c, north(100));

        var found = locations.freshWithin(zone, PICKUP, 5_000);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).location().distanceMetersTo(PICKUP)).isCloseTo(100, within(1.0));
    }

    @Test
    void seenMarkerExpiresAfterTheFreshnessWindow() {
        UUID c = UUID.randomUUID();
        locations.update(zone, c, north(100));
        Long ttl = redis.getExpire(CourierLocations.seenKey(c));
        assertThat(ttl).isBetween(55L, 60L);
    }

    @Test
    void staleCouriersAreSkippedAndPruned() {
        UUID fresh = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        locations.update(zone, fresh, north(300));
        locations.update(zone, stale, north(100));
        redis.delete(CourierLocations.seenKey(stale)); // as if its TTL ran out

        assertThat(locations.freshWithin(zone, PICKUP, 5_000)).extracting(CourierLocations.Nearby::courierId)
                .containsExactly(fresh);
        assertThat(redis.opsForZSet().score(CourierLocations.geoKey(zone), stale.toString())).isNull();
    }

    @Test
    void removeForgetsTheCourier() {
        UUID c = UUID.randomUUID();
        locations.update(zone, c, north(100));
        locations.remove(zone, c);

        assertThat(locations.freshWithin(zone, PICKUP, 5_000)).isEmpty();
        assertThat(redis.hasKey(CourierLocations.seenKey(c))).isFalse();
    }

    @Test
    void zonesAreSeparate() {
        locations.update(zone, UUID.randomUUID(), north(100));
        assertThat(locations.freshWithin(zone + "-other", PICKUP, 5_000)).isEmpty();
    }

    @Test
    void emptyZoneReturnsNothing() {
        assertThat(locations.freshWithin(zone, PICKUP, 5_000)).isEmpty();
    }
}
