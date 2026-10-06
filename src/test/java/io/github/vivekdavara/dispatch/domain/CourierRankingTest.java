package io.github.vivekdavara.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CourierRankingTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final GeoPoint PICKUP = new GeoPoint(42.3500, -71.0800);
    /** Metres per degree of latitude, so offsets north of PICKUP are easy to read. */
    private static final double M_PER_DEG_LAT = GeoPoint.EARTH_RADIUS_M * Math.toRadians(1.0);

    @Test
    void picksTheNearestCourier() {
        var near = courier(1, 300, 0);
        var far = courier(2, 900, 0);
        var best = CourierRanking.best(PICKUP, List.of(far, near), 5_000, NOW);
        assertThat(best).map(r -> r.candidate()).contains(near);
        assertThat(best.get().distanceMeters()).isBetween(299.0, 301.0);
    }

    @Test
    void excludesCouriersBeyondMaxPickupDistance() {
        var tooFar = courier(1, 6_000, 0);
        assertThat(CourierRanking.best(PICKUP, List.of(tooFar), 5_000, NOW)).isEmpty();
    }

    @Test
    void emptyCandidatesGiveNoCourier() {
        assertThat(CourierRanking.rank(PICKUP, List.of(), 5_000, NOW)).isEmpty();
    }

    @Test
    void nearTieGoesToTheLongestIdleCourier() {
        var nearButJustFreed = courier(1, 300, 1);
        var slightlyFartherLongIdle = courier(2, 330, 20);
        var ranked = CourierRanking.rank(PICKUP, List.of(nearButJustFreed, slightlyFartherLongIdle), 5_000, NOW);
        assertThat(ranked).extracting(CourierRanking.Ranked::candidate)
                .containsExactly(slightlyFartherLongIdle, nearButJustFreed);
    }

    @Test
    void distanceBeyondTheTieWindowBeatsIdleTime() {
        var near = courier(1, 300, 1);
        var farLongIdle = courier(2, 400, 60);
        var ranked = CourierRanking.rank(PICKUP, List.of(farLongIdle, near), 5_000, NOW);
        assertThat(ranked).extracting(CourierRanking.Ranked::candidate).containsExactly(near, farLongIdle);
    }

    @Test
    void tieWindowIsAnchoredAtTheNearestCourierOfEachBand() {
        // 300, 340, 380: 380 is within 50 m of 340 but not of 300, so it starts a new band.
        var a = courier(1, 300, 0);
        var b = courier(2, 340, 5);
        var c = courier(3, 380, 30);
        var ranked = CourierRanking.rank(PICKUP, List.of(c, b, a), 5_000, NOW);
        assertThat(ranked).extracting(CourierRanking.Ranked::candidate).containsExactly(b, a, c);
    }

    @Test
    void fullTieIsBrokenByCourierId() {
        var low = courier(1, 300, 5);
        var high = courier(2, 300, 5);
        var ranked = CourierRanking.rank(PICKUP, List.of(high, low), 5_000, NOW);
        assertThat(ranked).extracting(CourierRanking.Ranked::candidate).containsExactly(low, high);
    }

    @Test
    void idleSinceInTheFutureCountsAsZeroIdle() {
        var skewed = new CourierRanking.Candidate(new UUID(0, 1), north(300), NOW.plusSeconds(120));
        var idle = courier(2, 310, 1);
        var ranked = CourierRanking.rank(PICKUP, List.of(skewed, idle), 5_000, NOW);
        assertThat(ranked).extracting(CourierRanking.Ranked::candidate).containsExactly(idle, skewed);
    }

    /** A courier {@code metersNorth} of the pickup who has been idle for {@code idleMinutes}. */
    private static CourierRanking.Candidate courier(long id, double metersNorth, long idleMinutes) {
        return new CourierRanking.Candidate(new UUID(0, id), north(metersNorth), NOW.minusSeconds(idleMinutes * 60));
    }

    private static GeoPoint north(double meters) {
        return new GeoPoint(PICKUP.lat() + meters / M_PER_DEG_LAT, PICKUP.lng());
    }
}
