package io.github.vivekdavara.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** The scheduling rules on their own, with a fake pass and no database. */
class PassSchedulerTest {

    final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutDown() {
        pool.shutdownNow();
    }

    @Test
    void aRequestRunsOnePass() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        new PassScheduler(pool, zone -> ran.countDown()).request("z");

        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void requestsDuringAPassCoalesceIntoExactlyOneMore() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger passes = new AtomicInteger();
        PassScheduler s = new PassScheduler(pool, zone -> {
            if (passes.incrementAndGet() == 1) {
                firstStarted.countDown();
                await(release);
            }
        });

        s.request("z");
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 100; i++) {
            s.request("z");
        }
        assertThat(s.state("z")).isEqualTo(PassScheduler.RUNNING_AGAIN);
        release.countDown();

        awaitIdle(s, "z");
        assertThat(passes.get()).isEqualTo(2);
    }

    @Test
    void aZoneNeverHasTwoPassesAtOnce() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        PassScheduler s = new PassScheduler(pool, zone -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            sleep(2);
            inFlight.decrementAndGet();
        });

        hammer(8, 200, i -> s.request("z"));

        awaitIdle(s, "z");
        assertThat(maxInFlight.get()).isEqualTo(1);
    }

    @Test
    void differentZonesRunInParallel() throws Exception {
        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        PassScheduler s = new PassScheduler(pool, zone -> {
            bothRunning.countDown();
            await(release);
        });

        s.request("a");
        s.request("b");

        assertThat(bothRunning.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
    }

    /**
     * The no-lost-request rule: every request is followed by a pass that started after it. Each request records
     * a ticket; each pass records the highest ticket issued before it began. At the end, the last pass must have
     * seen the last ticket.
     */
    @RepeatedTest(5)
    void noRequestIsLost() throws Exception {
        AtomicInteger tickets = new AtomicInteger();
        AtomicInteger seen = new AtomicInteger();
        PassScheduler s = new PassScheduler(pool, zone -> {
            seen.accumulateAndGet(tickets.get(), Math::max);
            sleep(1);
        });

        hammer(6, 300, i -> {
            tickets.incrementAndGet();
            s.request("z");
        });

        awaitIdle(s, "z");
        assertThat(seen.get()).isEqualTo(tickets.get());
    }

    @Test
    void aFailingPassDoesNotWedgeTheZone() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch secondRan = new CountDownLatch(1);
        PassScheduler s = new PassScheduler(pool, zone -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("redis down");
            }
            secondRan.countDown();
        });

        s.request("z");
        awaitIdle(s, "z");
        s.request("z");

        assertThat(secondRan.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aRejectedSubmitLeavesTheZoneRequestable() {
        ExecutorService closed = Executors.newSingleThreadExecutor();
        closed.shutdown();
        List<String> ran = new ArrayList<>();
        PassScheduler s = new PassScheduler(closed, ran::add);

        s.request("z");

        assertThat(s.state("z")).isEqualTo(PassScheduler.IDLE);
        assertThat(ran).isEmpty();
    }

    @Test
    void passesSeeTheirOwnZoneId() throws Exception {
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(3);
        PassScheduler s = new PassScheduler(pool, zone -> {
            counts.merge(zone, 1, Integer::sum);
            done.countDown();
        });

        s.request("x");
        s.request("y");
        s.request("w");

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(counts).containsOnlyKeys("x", "y", "w");
    }

    static void hammer(int threads, int perThread, Consumer<Integer> request) throws InterruptedException {
        ExecutorService callers = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            callers.execute(() -> {
                await(start);
                for (int i = 0; i < perThread; i++) {
                    request.accept(i);
                }
            });
        }
        start.countDown();
        callers.shutdown();
        assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    static void awaitIdle(PassScheduler s, String zone) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (s.state(zone) != PassScheduler.IDLE) {
            assertThat(System.nanoTime()).as("zone %s still busy", zone).isLessThan(deadline);
            Thread.sleep(1);
        }
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
