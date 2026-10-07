package io.github.vivekdavara.dispatch.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vivekdavara.dispatch.order.CreateOrderRequest.Location;
import io.github.vivekdavara.dispatch.support.TestZone;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Many clients retrying the same request at the same instant: exactly one order is created and every caller
 * gets that order. Commits for real (no test transaction), so the race goes through Postgres' unique index.
 */
@SpringBootTest
class ConcurrentOrderCreationTest {

    static final int CALLERS = 16;

    @Autowired
    OrderService service;

    @Autowired
    JdbcTemplate jdbc;

    TestZone zone;

    @BeforeEach
    void setUp() {
        zone = TestZone.create(jdbc, "race");
    }

    @AfterEach
    void tearDown() {
        zone.delete();
    }

    CreateOrderRequest request(double dropoffLat) {
        return new CreateOrderRequest(zone.id(), new Location(TestZone.CENTER_LAT, TestZone.CENTER_LNG),
                new Location(dropoffLat, -71.06), null);
    }

    /** Runs every task at once (released by one latch) and returns each one's result or exception. */
    static <T> List<Object> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<T> f : futures) {
                try {
                    outcomes.add(f.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @RepeatedTest(5)
    void simultaneousRetriesCreateExactlyOneOrder() throws Exception {
        List<Callable<OrderService.Created>> tasks = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            tasks.add(() -> service.create("same-key-" + zone.id(), request(42.36)));
        }
        List<Object> outcomes = race(tasks);

        assertThat(outcomes).allMatch(o -> o instanceof OrderService.Created);
        List<OrderService.Created> created = outcomes.stream().map(o -> (OrderService.Created) o).toList();
        assertThat(created.stream().map(c -> c.order().id()).distinct()).hasSize(1);
        assertThat(created.stream().filter(c -> !c.replayed())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE zone_id = ?", Integer.class, zone.id()))
                .isEqualTo(1);
    }

    @RepeatedTest(5)
    void simultaneousConflictingBodiesLetExactlyOneWin() throws Exception {
        List<Callable<OrderService.Created>> tasks = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            double dropoffLat = 42.36 + i * 0.001; // every caller sends a different body
            tasks.add(() -> service.create("contested-" + zone.id(), request(dropoffLat)));
        }
        List<Object> outcomes = race(tasks);

        assertThat(outcomes.stream().filter(o -> o instanceof OrderService.Created)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o instanceof OrderService.IdempotencyKeyReusedException))
                .hasSize(CALLERS - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE zone_id = ?", Integer.class, zone.id()))
                .isEqualTo(1);
    }
}
