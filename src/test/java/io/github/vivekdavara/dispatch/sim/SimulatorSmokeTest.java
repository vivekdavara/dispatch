package io.github.vivekdavara.dispatch.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vivekdavara.dispatch.courier.CourierLocations;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The simulator's 1,000-event scenario against the real app (loop on, real sockets), so CI keeps the simulator
 * working and checks the whole system under a little load: every order created exactly once despite retried
 * POSTs, offered, accepted and delivered; retried answers acknowledged; couriers who dropped their socket came
 * back; and the database agrees. The grace is 1.5 s here, so the scenario's short drops (at most 0.4 s) reconnect
 * in time and its long ones (3 to 4 s) get the courier taken offline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dispatch.loop.enabled=true",
        "dispatch.sockets.reconnect-grace=1500ms",
        "dispatch.loop.sweep-interval=1s"})
@DirtiesContext
class SimulatorSmokeTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    Simulator.Report report;

    @AfterEach
    void cleanUp() {
        if (report == null) {
            return;
        }
        for (String zone : report.zoneIds()) {
            List<UUID> couriers = jdbc.queryForList("SELECT id FROM couriers WHERE zone_id = ?", UUID.class, zone);
            jdbc.update("DELETE FROM assignments WHERE order_id IN (SELECT id FROM orders WHERE zone_id = ?)", zone);
            jdbc.update("DELETE FROM orders WHERE zone_id = ?", zone);
            jdbc.update("DELETE FROM couriers WHERE zone_id = ?", zone);
            jdbc.update("DELETE FROM zones WHERE id = ?", zone);
            redis.delete(CourierLocations.geoKey(zone));
            couriers.forEach(c -> redis.delete(CourierLocations.seenKey(c)));
        }
    }

    @Test
    void theSmallScenarioRunsCleanlyEndToEnd() throws InterruptedException {
        Scenario scenario = Scenario.generate(Scenario.Config.small());
        Simulator.Options options = new Simulator.Options(URI.create("http://localhost:" + port), 1.0, "smoke",
                Duration.ofSeconds(60), null, CourierBot.Behaviour.quick());

        report = new Simulator(scenario, options).run();

        assertThat(report.notes()).isEmpty();
        assertThat(report.count("orders", "created")).isEqualTo(200);
        assertThat(report.count("orders", "errors")).isZero();
        assertThat(report.count("orders", "duplicatesCreated")).isZero();
        assertThat(report.count("orders", "retried")).isPositive();
        assertThat(report.count("orders", "retriesConsistent")).isEqualTo(report.count("orders", "retried"));
        assertThat(report.count("orders", "offered")).isEqualTo(200);
        assertThat(report.count("orders", "delivered")).isEqualTo(200);
        assertThat(report.count("replay", "pingsSent")).isEqualTo(800);
        assertThat(report.count("couriers", "answerRetries")).isPositive();
        assertThat(report.count("couriers", "answerRetriesRejected")).isZero();
        assertThat(report.count("couriers", "socketErrors")).isZero();
        assertThat(report.count("couriers", "deliveryErrors")).isZero();
        assertThat(report.count("couriers", "reconnects")).isEqualTo(report.count("couriers", "socketDrops"));
        // Only a drop longer than the grace can cost a courier their status; the scenario has two of those.
        assertThat(report.count("couriers", "foundOfflineOnReconnect")).isBetween(0L, 2L);
        assertThat(report.firstOffer().count()).isEqualTo(200);
        assertThat(report.firstOfferByTier().keySet()).containsExactlyInAnyOrder("STANDARD", "PRIORITY");
        assertThat(report.firstOfferByTier().values().stream().mapToInt(LatencySummary::count).sum()).isEqualTo(200);

        String run = report.runId() + "-z%";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE zone_id LIKE ?", Long.class, run))
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE zone_id LIKE ? AND status <> 'DELIVERED'",
                Long.class, run)).isZero();
        // Every order delivered once: 200 completed assignments for 200 delivered orders. Declines and expiries
        // are the other assignments.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM assignments a JOIN orders o ON o.id = a.order_id
                 WHERE o.zone_id LIKE ? AND a.status = 'COMPLETED'""", Long.class, run)).isEqualTo(200);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM assignments a JOIN orders o ON o.id = a.order_id
                 WHERE o.zone_id LIKE ? AND a.status IN ('OFFERED', 'ACCEPTED')""", Long.class, run)).isZero();
    }
}
