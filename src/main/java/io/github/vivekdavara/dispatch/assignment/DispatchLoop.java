package io.github.vivekdavara.dispatch.assignment;

import io.github.vivekdavara.dispatch.config.DispatchProperties;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The event-driven assignment loop. Three things start a pass over a zone:
 *
 * <ol>
 *   <li>a {@link DispatchNeeded} event (order created, courier available, offer declined or expired, delivery
 *       done), handled as soon as it's published;</li>
 *   <li>the expiry tick, which expires overdue offers every {@code expiry-interval} (each expiry publishes an
 *       event, so the freed order is re-offered right away);</li>
 *   <li>the sweep, every {@code sweep-interval}, over zones that still have pending orders. It is the safety net
 *       for changes that send no event: chiefly a courier driving into range, since location pings are far too
 *       frequent to trigger passes.</li>
 * </ol>
 *
 * All three go through {@link PassScheduler}, so passes per zone are serial and bursts coalesce. Turned off with
 * {@code dispatch.loop.enabled=false} (the tests do that, to drive the engine by hand); the manual
 * {@code POST /zones/{id}/dispatch} works either way.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "dispatch.loop.enabled", havingValue = "true", matchIfMissing = true)
public class DispatchLoop {

    private static final Logger log = LoggerFactory.getLogger(DispatchLoop.class);

    private final OfferService offers;
    private final JdbcTemplate jdbc;
    private final ExecutorService executor;
    private final PassScheduler scheduler;

    public DispatchLoop(AssignmentEngine engine, OfferService offers, JdbcTemplate jdbc, DispatchProperties props) {
        this.offers = offers;
        this.jdbc = jdbc;
        AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(props.loop().threads(), r -> {
            Thread t = new Thread(r, "dispatch-pass-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.scheduler = new PassScheduler(executor, engine::dispatchZone);
    }

    @EventListener
    public void on(DispatchNeeded event) {
        log.debug("pass requested for zone {}: {}", event.zoneId(), event.reason());
        scheduler.request(event.zoneId());
    }

    @Scheduled(fixedDelayString = "${dispatch.loop.expiry-interval:1s}")
    public void expireOverdueOffers() {
        List<Assignment> expired = offers.expireDue();
        if (!expired.isEmpty()) {
            log.info("expired {} unanswered offer(s)", expired.size());
        }
    }

    @Scheduled(fixedDelayString = "${dispatch.loop.sweep-interval:5s}",
            initialDelayString = "${dispatch.loop.sweep-interval:5s}")
    public void sweepPendingZones() {
        // Served by the partial index on pending orders.
        for (String zone : jdbc.queryForList("SELECT DISTINCT zone_id FROM orders WHERE status = 'PENDING'",
                String.class)) {
            on(new DispatchNeeded(zone, DispatchNeeded.Reason.SWEEP));
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
