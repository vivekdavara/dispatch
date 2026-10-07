package io.github.vivekdavara.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.support.Fixtures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Eight engine passes over the same zone at the same instant, all seeing the same pending orders and the same
 * free couriers. Every courier and every order must end up in at most one offer, with no constraint errors.
 */
@SpringBootTest
class ConcurrentDispatchTest {

    static final int PASSES = 8;
    static final int COURIERS = 12;
    static final int ORDERS = 30;

    @Autowired
    AssignmentEngine engine;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CourierLocations locations;

    Fixtures f;

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc, redis, locations, "race-engine");
        for (int i = 0; i < COURIERS; i++) {
            // 0 to 1.1 km north of every pickup: all passes want the same nearest couriers first.
            f.availableCourier(Fixtures.north(i * 100.0), Duration.ofMinutes(i % 4));
        }
        for (int i = 0; i < ORDERS; i++) {
            f.order(i % 3 == 0 ? OrderTier.PRIORITY : OrderTier.STANDARD, Duration.ofSeconds(i * 10L));
        }
    }

    @AfterEach
    void tearDown() {
        f.cleanUp();
    }

    @RepeatedTest(5)
    void parallelPassesNeverDoubleAssign() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(PASSES);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Offer>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < PASSES; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return engine.dispatchZone(f.zoneId());
                }));
            }
            start.countDown();
            List<Offer> all = new ArrayList<>();
            for (Future<List<Offer>> fut : futures) {
                all.addAll(fut.get()); // rethrows any constraint violation from a pass
            }

            // Every free courier got exactly one order, and no order went to two couriers.
            assertThat(all).hasSize(COURIERS);
            assertThat(all.stream().map(Offer::courierId).distinct()).hasSize(COURIERS);
            assertThat(all.stream().map(Offer::orderId).distinct()).hasSize(COURIERS);
        } finally {
            pool.shutdownNow();
        }

        // And the database agrees: no stray rows from rolled-back claims.
        String zone = f.zoneId();
        assertThat(count("SELECT count(*) FROM assignments a JOIN orders o ON o.id = a.order_id WHERE o.zone_id = ?",
                zone)).isEqualTo(COURIERS);
        assertThat(count("SELECT count(*) FROM orders WHERE zone_id = ? AND status = 'OFFERED'", zone))
                .isEqualTo(COURIERS);
        assertThat(count("SELECT count(*) FROM orders WHERE zone_id = ? AND status = 'PENDING'", zone))
                .isEqualTo(ORDERS - COURIERS);
        assertThat(count("SELECT count(*) FROM couriers WHERE zone_id = ? AND status = 'OFFERED'", zone))
                .isEqualTo(COURIERS);
    }

    int count(String sql, String zone) {
        return jdbc.queryForObject(sql, Integer.class, zone);
    }
}
