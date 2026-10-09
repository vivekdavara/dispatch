package io.github.vivekdavara.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.support.Fixtures;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The snapshot pass against the per-order pass it replaced: on identical zones (same couriers, statuses, idle
 * times, positions, orders and refusals, built from a seed) both must make exactly the same offers. Plus the two
 * shortcuts the snapshot allows: no AVAILABLE courier means no work, and the pass stops when free couriers run out.
 * A snapshot going stale mid-pass is covered by {@code ConcurrentDispatchTest}, whose eight racing passes use the
 * snapshot strategy (the default) and keep claiming couriers out from under each other's snapshots.
 */
@SpringBootTest
class CandidateSnapshotTest {

    @Autowired
    AssignmentEngine engine;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CourierLocations locations;

    @Autowired
    MeterRegistry meters;

    final List<Fixtures> zones = new ArrayList<>();

    @AfterEach
    void tearDown() {
        zones.forEach(Fixtures::cleanUp);
    }

    record CourierSpec(String status, boolean pinged, double northM, double eastM, int idleSeconds) {
    }

    record OrderSpec(OrderTier tier, int ageSeconds, double northM, double eastM) {
    }

    /** A random zone layout: who's where, in what state, which orders wait, and who refused what. */
    record Layout(List<CourierSpec> couriers, List<OrderSpec> orders, List<int[]> refusals) {

        static Layout random(long seed) {
            Random rnd = new Random(seed);
            List<CourierSpec> couriers = new ArrayList<>();
            for (int i = 0; i < 14; i++) {
                double roll = rnd.nextDouble();
                String status = roll < 0.7 ? "AVAILABLE" : roll < 0.8 ? "BUSY" : roll < 0.9 ? "OFFLINE" : "AVAILABLE";
                boolean pinged = roll < 0.9; // the last 10%: AVAILABLE but no fresh ping
                // Distinct idle times a minute apart, so the tie-break can't depend on insert timing.
                couriers.add(new CourierSpec(status, pinged, (rnd.nextDouble() - 0.5) * 9_000,
                        (rnd.nextDouble() - 0.5) * 9_000, 60 * (i + 1)));
            }
            List<OrderSpec> orders = new ArrayList<>();
            for (int i = 0; i < 18; i++) {
                orders.add(new OrderSpec(rnd.nextDouble() < 0.25 ? OrderTier.PRIORITY : OrderTier.STANDARD,
                        30 * (i + 1) + 7 * rnd.nextInt(3), (rnd.nextDouble() - 0.5) * 4_000,
                        (rnd.nextDouble() - 0.5) * 4_000));
            }
            List<int[]> refusals = new ArrayList<>();
            for (int o = 0; o < orders.size(); o++) {
                for (int c = 0; c < couriers.size(); c++) {
                    if (rnd.nextDouble() < 0.2) {
                        refusals.add(new int[] {o, c});
                    }
                }
            }
            return new Layout(couriers, orders, refusals);
        }

        /** Builds this layout in a fresh zone; returns the courier and order ids in spec order. */
        Built build(Fixtures f, JdbcTemplate jdbc) {
            List<UUID> courierIds = new ArrayList<>();
            for (CourierSpec c : couriers) {
                UUID id = f.courier(c.status(), Duration.ofSeconds(c.idleSeconds()));
                if (c.pinged()) {
                    f.ping(id, at(c.northM(), c.eastM()));
                }
                courierIds.add(id);
            }
            List<UUID> orderIds = new ArrayList<>();
            for (OrderSpec o : orders) {
                orderIds.add(f.order(o.tier(), Duration.ofSeconds(o.ageSeconds()), at(o.northM(), o.eastM())));
            }
            for (int[] r : refusals) {
                jdbc.update("""
                        INSERT INTO assignments (order_id, courier_id, status, distance_m, offered_at, responded_at)
                        VALUES (?, ?, ?, 100, now() - interval '2 minutes', now() - interval '1 minute')""",
                        orderIds.get(r[0]), courierIds.get(r[1]), r[1] % 2 == 0 ? "DECLINED" : "EXPIRED");
            }
            return new Built(courierIds, orderIds);
        }
    }

    record Built(List<UUID> couriers, List<UUID> orders) {

        /** Offers as (order number, courier number, distance to the metre), in the order the pass made them. */
        List<String> describe(List<Offer> offers) {
            return offers.stream().map(o -> orders.indexOf(o.orderId()) + "->" + couriers.indexOf(o.courierId())
                    + "@" + Math.round(o.distanceMeters())).toList();
        }
    }

    static GeoPoint at(double northM, double eastM) {
        GeoPoint c = Fixtures.CENTER;
        return new GeoPoint(c.lat() + northM / 111_195.0,
                c.lng() + eastM / (111_195.0 * Math.cos(Math.toRadians(c.lat()))));
    }

    Fixtures zone(String prefix) {
        Fixtures f = new Fixtures(jdbc, redis, locations, prefix);
        zones.add(f);
        return f;
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
    void theSnapshotPassMakesExactlyTheOffersThePerOrderPassMakes(long seed) {
        Layout layout = Layout.random(seed);
        Fixtures before = zone("per-order");
        Fixtures after = zone("snapshot");
        Built a = layout.build(before, jdbc);
        Built b = layout.build(after, jdbc);

        List<String> perOrder = a.describe(engine.dispatchZone(before.zoneId(), false));
        List<String> snapshot = b.describe(engine.dispatchZone(after.zoneId(), true));

        assertThat(snapshot).isEqualTo(perOrder);
        assertThat(perOrder).isNotEmpty();
    }

    @Test
    void withNoAvailableCourierThePassLooksAtNoOrders() {
        Fixtures f = zone("snap-none");
        UUID busy = f.courier("BUSY", Duration.ofMinutes(5));
        f.ping(busy, Fixtures.north(100));
        for (int i = 0; i < 5; i++) {
            f.order(OrderTier.STANDARD, Duration.ofMinutes(i + 1));
        }
        long passes = meters.get("dispatch.pass").timer().count();
        double examined = meters.get("dispatch.pass.orders").summary().totalAmount();

        assertThat(engine.dispatchZone(f.zoneId(), true)).isEmpty();

        assertThat(meters.get("dispatch.pass").timer().count()).isEqualTo(passes + 1);
        assertThat(meters.get("dispatch.pass.orders").summary().totalAmount()).isEqualTo(examined);
    }

    @Test
    void thePassStopsWhenTheFreeCouriersRunOut() {
        Fixtures f = zone("snap-stop");
        f.availableCourier(Fixtures.north(200), Duration.ofMinutes(3));
        f.availableCourier(Fixtures.north(400), Duration.ofMinutes(2));
        for (int i = 0; i < 6; i++) {
            f.order(OrderTier.STANDARD, Duration.ofMinutes(i + 1));
        }
        double examined = meters.get("dispatch.pass.orders").summary().totalAmount();

        List<Offer> offers = engine.dispatchZone(f.zoneId(), true);

        assertThat(offers).hasSize(2);
        // The per-order pass would have looked at all six; this one stopped after the two it could serve.
        assertThat(meters.get("dispatch.pass.orders").summary().totalAmount()).isEqualTo(examined + 2);
    }
}
