package io.github.vivekdavara.dispatch.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioTest {

    static final Scenario STANDARD = Scenario.generate(Scenario.Config.standard());

    static List<Scenario.NewOrder> orders(Scenario s) {
        return s.events().stream().filter(e -> e instanceof Scenario.NewOrder)
                .map(e -> (Scenario.NewOrder) e).toList();
    }

    static List<Scenario.Ping> pings(Scenario s) {
        return s.events().stream().filter(e -> e instanceof Scenario.Ping).map(e -> (Scenario.Ping) e).toList();
    }

    @Test
    void theStandardScenarioIsFiftyThousandEvents() {
        assertThat(STANDARD.events()).hasSize(50_000);
        assertThat(orders(STANDARD)).hasSize(10_000);
        assertThat(pings(STANDARD)).hasSize(40_000);
        assertThat(STANDARD.drops()).hasSize(40);
    }

    @Test
    void theSameSeedGivesTheSameScenarioAndAnotherSeedDoesNot() {
        Scenario again = Scenario.generate(Scenario.Config.standard());
        Scenario.Config c = Scenario.Config.standard();
        Scenario other = Scenario.generate(new Scenario.Config(c.seed() + 1, c.zones(), c.couriersPerZone(),
                c.orders(), c.pings(), c.span(), c.priorityShare(), c.retryShare(), c.rushStart(), c.rushLength(),
                c.rushFactor(), c.shortDrops(), c.shortDropMax(), c.longDrops(), c.longDropMin(), c.longDropMax()));

        assertThat(again.events()).isEqualTo(STANDARD.events());
        assertThat(again.drops()).isEqualTo(STANDARD.drops());
        assertThat(other.events()).isNotEqualTo(STANDARD.events());
    }

    @Test
    void eventsAreInTimeOrderInsideTheSpan() {
        long span = STANDARD.config().span().toMillis();
        long previous = 0;
        for (Scenario.Event e : STANDARD.events()) {
            assertThat(e.atMs()).isBetween(previous, span - 1);
            previous = e.atMs();
        }
    }

    @Test
    void everyPickupIsInsideItsZoneAndEveryPingInsideItsCouriersZone() {
        double radiusM = Scenario.ZONE_RADIUS_KM * 1000;
        for (Scenario.NewOrder o : orders(STANDARD)) {
            assertThat(o.pickup().distanceMetersTo(Scenario.centre(o.zone()))).isLessThan(radiusM);
            assertThat(o.dropoff().distanceMetersTo(o.pickup())).isLessThanOrEqualTo(3_000.0 + 1e-6);
        }
        for (Scenario.Ping p : pings(STANDARD)) {
            assertThat(p.at().distanceMetersTo(Scenario.centre(STANDARD.zoneOf(p.courier())))).isLessThan(radiusM);
        }
    }

    @Test
    void couriersMoveAtMostOneStepBetweenPingsAndPingEvenly() {
        Map<Integer, Scenario.Ping> last = new HashMap<>();
        Map<Integer, Integer> perCourier = new HashMap<>();
        for (Scenario.Ping p : pings(STANDARD)) {
            Scenario.Ping before = last.put(p.courier(), p);
            if (before != null) {
                assertThat(p.at().distanceMetersTo(before.at())).isLessThanOrEqualTo(Scenario.MAX_STEP_M + 1e-6);
            }
            perCourier.merge(p.courier(), 1, Integer::sum);
        }
        assertThat(perCourier).hasSize(STANDARD.config().couriers());
        // 40,000 pings over 240 couriers: 166 or 167 each.
        assertThat(perCourier.values()).allSatisfy(n -> assertThat(n).isBetween(166, 167));
    }

    @Test
    void theRushIsDenserThanTheRestOfTheSpan() {
        Scenario.Config c = STANDARD.config();
        long start = c.rushStart().toMillis();
        long end = start + c.rushLength().toMillis();
        long inRush = orders(STANDARD).stream().filter(o -> o.atMs() >= start && o.atMs() < end).count();
        double rushRate = (double) inRush / c.rushLength().toMillis();
        double otherRate = (double) (c.orders() - inRush) / (c.span().toMillis() - c.rushLength().toMillis());

        // Expected ratio 3.0; with 10,000 orders the sampling noise is a few percent.
        assertThat(rushRate / otherRate).isBetween(2.7, 3.3);
    }

    @Test
    void sharesOfPriorityOrdersAndRetriesMatchTheConfig() {
        List<Scenario.NewOrder> orders = orders(STANDARD);
        long priority = orders.stream().filter(o -> o.tier() == OrderTier.PRIORITY).count();
        long retried = orders.stream().filter(o -> o.retryAfterMs() >= 0).count();

        assertThat(priority / 10_000.0).isBetween(0.18, 0.22);
        assertThat(retried / 10_000.0).isBetween(0.04, 0.06);
        assertThat(orders).filteredOn(o -> o.retryAfterMs() >= 0)
                .allSatisfy(o -> assertThat(o.retryAfterMs()).isBetween(50L, 500L));
    }

    @Test
    void dropsAreShortOrLongAsConfigured() {
        Scenario.Config c = STANDARD.config();
        long shortMax = c.shortDropMax().toMillis();
        long longMin = c.longDropMin().toMillis();
        long nShort = STANDARD.drops().stream().filter(d -> d.downMs() <= shortMax).count();
        long nLong = STANDARD.drops().stream().filter(d -> d.downMs() >= longMin).count();

        assertThat(nShort).isEqualTo(c.shortDrops());
        assertThat(nLong).isEqualTo(c.longDrops());
        assertThat(STANDARD.drops()).allSatisfy(d -> assertThat(d.courier()).isBetween(0, c.couriers() - 1));
    }

    @Test
    void aStepThatWouldLeaveTheZoneHeadsBackToTheCentre() {
        GeoPoint home = Scenario.centre(0);
        GeoPoint edge = Scenario.offset(home, Scenario.ZONE_RADIUS_KM * 950 - 1, 0);
        java.util.Random rnd = new java.util.Random(1);
        GeoPoint p = edge;
        for (int i = 0; i < 1_000; i++) {
            p = Scenario.step(rnd, p, home);
            assertThat(p.distanceMetersTo(home)).isLessThan(Scenario.ZONE_RADIUS_KM * 950 + 1e-6);
        }
    }

    @Test
    void aRushThatDoesNotFitIsRejected() {
        assertThatThrownBy(() -> new Scenario.Config(1, 1, 1, 10, 10, Duration.ofSeconds(10), 0, 0,
                Duration.ofSeconds(8), Duration.ofSeconds(5), 2, 0, Duration.ofSeconds(1), 0, Duration.ofSeconds(1),
                Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
