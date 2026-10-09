package io.github.vivekdavara.dispatch.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.vivekdavara.dispatch.domain.GeoPoint;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Replays a {@link Scenario} against a running Dispatch through its public interfaces only: HTTP for zones,
 * couriers, pings, orders, pickup and delivery, and one WebSocket per courier for offers and answers (see
 * {@link CourierBot}). It measures what a courier app sees: how long after the customer's POST the first offer
 * for the order arrives on a courier's socket.
 *
 * <p>Run it with {@code scripts/simulate.sh}, which starts the app on a fresh database first. Options:
 * {@code --base-url} (default {@code http://localhost:8101}), {@code --speed} (replay speed, default 1),
 * {@code --label}, {@code --out} (JSON report path), {@code --db-url} (adds the server-side latency from Postgres
 * timestamps; put the user in the URL, {@code ?user=...}), {@code --drain-seconds} (how long to wait for the last
 * deliveries, default 180), {@code --scenario small} (the smoke test's 1,000-event scenario instead of 50,000).
 */
public final class Simulator {

    /** How to run a scenario. */
    public record Options(URI baseUrl, double speed, String label, Duration drain, String dbUrl,
                          CourierBot.Behaviour behaviour) {
    }

    /** The JSON report (and what the smoke test asserts on). */
    public record Report(String label, String runId, Instant startedAt, Map<String, Object> scenario,
                         Map<String, Object> replay, Map<String, Object> orders, Map<String, Object> couriers,
                         LatencySummary firstOffer, LatencySummary firstOfferServer, LatencySummary accepted,
                         Map<String, Object> server, List<String> zoneIds, List<String> notes) {

        @SuppressWarnings("unchecked")
        long count(String section, String key) {
            Map<String, Object> m = switch (section) {
                case "orders" -> orders;
                case "couriers" -> couriers;
                case "replay" -> replay;
                default -> throw new IllegalArgumentException(section);
            };
            return ((Number) m.get(key)).longValue();
        }
    }

    private final Scenario scenario;
    private final Options options;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Recorder rec = new Recorder();
    private final String runId = "sim-" + UUID.randomUUID().toString().substring(0, 8);
    private final DispatchApi api;

    public Simulator(Scenario scenario, Options options) {
        this.scenario = scenario;
        this.options = options;
        this.api = new DispatchApi(options.baseUrl(), json);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        boolean small = "small".equals(a.get("scenario"));
        Options options = new Options(URI.create(a.getOrDefault("base-url", "http://localhost:8101")),
                Double.parseDouble(a.getOrDefault("speed", "1")), a.getOrDefault("label", "run"),
                Duration.ofSeconds(Long.parseLong(a.getOrDefault("drain-seconds", "180"))), a.get("db-url"),
                small ? CourierBot.Behaviour.quick() : CourierBot.Behaviour.standard());
        Scenario.Config config = small ? Scenario.Config.small() : Scenario.Config.standard();
        Report report = new Simulator(Scenario.generate(config), options).run();
        Path out = Path.of(a.getOrDefault("out", "target/sim/" + options.label() + ".json"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
        System.out.println("report: " + out);
        System.exit(0); // HTTP client threads are non-daemon
    }

    public Report run() throws InterruptedException {
        Instant startedAt = Instant.now();
        Scenario.Config c = scenario.config();
        ScheduledExecutorService timers = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "sim-timer");
            t.setDaemon(true);
            return t;
        });
        List<String> zoneIds = new ArrayList<>();
        List<CourierBot> bots = new ArrayList<>();
        try {
            log("setting up %d zones and %d couriers (run %s)", c.zones(), c.couriers(), runId);
            setUp(zoneIds, bots, timers);

            log("replaying %d events (%d orders, %d pings) and %d socket drops at speed %.1f",
                    c.events(), c.orders(), c.pings(), scenario.drops().size(), options.speed());
            long replayStart = System.nanoTime();
            replay(zoneIds, bots, timers, replayStart);
            double replaySeconds = (System.nanoTime() - replayStart) / 1e9;

            log("replay done in %.1f s; waiting up to %d s for deliveries", replaySeconds, options.drain().toSeconds());
            drain(c.orders());
            double totalSeconds = (System.nanoTime() - replayStart) / 1e9;

            Report report = report(startedAt, zoneIds, replaySeconds, totalSeconds);
            print(report);
            return report;
        } finally {
            bots.forEach(CourierBot::close);
            timers.shutdownNow();
        }
    }

    private void setUp(List<String> zoneIds, List<CourierBot> bots, ScheduledExecutorService timers) {
        Scenario.Config c = scenario.config();
        for (int z = 0; z < c.zones(); z++) {
            String id = runId + "-z" + z;
            GeoPoint centre = Scenario.centre(z);
            api.call("PUT", "/api/v1/zones/" + id, Map.of("name", "Simulated zone " + z, "centerLat", centre.lat(),
                    "centerLng", centre.lng(), "radiusKm", Scenario.ZONE_RADIUS_KM));
            zoneIds.add(id);
        }
        // Each courier starts where its first ping will put it.
        Map<Integer, GeoPoint> start = new HashMap<>();
        for (Scenario.Event e : scenario.events()) {
            if (e instanceof Scenario.Ping p) {
                start.putIfAbsent(p.courier(), p.at());
            }
        }
        List<CompletableFuture<Void>> ready = new ArrayList<>();
        for (int i = 0; i < c.couriers(); i++) {
            JsonNode courier = api.call("POST", "/api/v1/couriers",
                    Map.of("zoneId", zoneIds.get(scenario.zoneOf(i)), "name", "sim courier " + i));
            CourierBot bot = new CourierBot(i, courier.path("id").asText(), api, json, rec, timers,
                    options.behaviour(), options.speed(), c.seed());
            bots.add(bot);
            GeoPoint at = start.getOrDefault(i, Scenario.centre(scenario.zoneOf(i)));
            ready.add(CompletableFuture.runAsync(() -> {
                bot.connect();
                api.call("PUT", "/api/v1/couriers/" + bot.courierId + "/location", Map.of("lat", at.lat(), "lng", at.lng()));
                api.call("PUT", "/api/v1/couriers/" + bot.courierId + "/status", Map.of("status", "AVAILABLE"));
            }, timers));
        }
        ready.forEach(CompletableFuture::join);
    }

    private void replay(List<String> zoneIds, List<CourierBot> bots, ScheduledExecutorService timers, long t0) {
        List<Scenario.SocketDrop> drops = scenario.drops();
        int nextDrop = 0;
        for (Scenario.Event e : scenario.events()) {
            while (nextDrop < drops.size() && drops.get(nextDrop).atMs() <= e.atMs()) {
                Scenario.SocketDrop d = drops.get(nextDrop++);
                waitUntil(t0, d.atMs());
                CourierBot bot = bots.get(d.courier());
                if (bot.connected()) {
                    bot.drop();
                    timers.schedule(bot::reconnect, (long) (d.downMs() * 1000 / options.speed()), TimeUnit.MICROSECONDS);
                }
            }
            waitUntil(t0, e.atMs());
            switch (e) {
                case Scenario.Ping p -> ping(bots.get(p.courier()), p.at());
                case Scenario.NewOrder o -> order(zoneIds, o, timers);
            }
        }
    }

    private void ping(CourierBot bot, GeoPoint at) {
        api.callAsync("PUT", "/api/v1/couriers/" + bot.courierId + "/location", Map.of("lat", at.lat(), "lng", at.lng()))
                .whenComplete((status, e) -> {
                    if (e == null && status == 204) {
                        rec.pingsSent.increment();
                    } else {
                        rec.pingErrors.increment();
                    }
                });
    }

    private void order(List<String> zoneIds, Scenario.NewOrder o, ScheduledExecutorService timers) {
        String key = runId + "-o" + o.index();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("zoneId", zoneIds.get(o.zone()));
        body.put("pickup", Map.of("lat", o.pickup().lat(), "lng", o.pickup().lng()));
        body.put("dropoff", Map.of("lat", o.dropoff().lat(), "lng", o.dropoff().lng()));
        body.put("tier", o.tier().name());
        rec.orderSentAt.put(o.index(), System.nanoTime());
        api.createOrder(key, body).whenComplete((r, e) -> settle(o.index(), r, e, rec.firstResponse));
        if (o.retryAfterMs() >= 0) {
            timers.schedule(() -> api.createOrder(key, body).whenComplete((r, e) -> settle(o.index(), r, e,
                    rec.retryResponse)), (long) (o.retryAfterMs() * 1000 / options.speed()), TimeUnit.MICROSECONDS);
        }
    }

    private void settle(int index, DispatchApi.OrderResponse r, Throwable e, Map<Integer, DispatchApi.OrderResponse> into) {
        if (e != null || r.orderId() == null) {
            rec.orderErrors.increment();
            return;
        }
        into.put(index, r);
        rec.orderIdOf.putIfAbsent(index, r.orderId());
    }

    private void waitUntil(long t0, long atMs) {
        long target = t0 + (long) (atMs * 1_000_000 / options.speed());
        long now;
        while ((now = System.nanoTime()) < target) {
            LockSupport.parkNanos(target - now);
        }
        rec.replayLag(now - target);
    }

    private void drain(int orders) throws InterruptedException {
        long deadline = System.nanoTime() + options.drain().toNanos();
        while (System.nanoTime() < deadline && rec.deliveredAt.size() < orders) {
            Thread.sleep(100);
        }
    }

    private Report report(Instant startedAt, List<String> zoneIds, double replaySeconds, double totalSeconds) {
        Scenario.Config c = scenario.config();
        Map<String, Object> scenarioInfo = new LinkedHashMap<>();
        scenarioInfo.put("seed", c.seed());
        scenarioInfo.put("events", c.events());
        scenarioInfo.put("orders", c.orders());
        scenarioInfo.put("pings", c.pings());
        scenarioInfo.put("zones", c.zones());
        scenarioInfo.put("couriers", c.couriers());
        scenarioInfo.put("socketDrops", scenario.drops().size());
        scenarioInfo.put("spanSeconds", c.span().toSeconds());

        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("speed", options.speed());
        replay.put("replaySeconds", round(replaySeconds));
        replay.put("totalSeconds", round(totalSeconds));
        replay.put("maxLagMs", round(rec.maxReplayLagNanos.get() / 1e6));
        replay.put("pingsSent", rec.pingsSent.sum());
        replay.put("pingErrors", rec.pingErrors.sum());

        int created = 0;
        int retries = 0;
        int retriesConsistent = 0;
        int duplicates = 0;
        for (int i = 0; i < c.orders(); i++) {
            DispatchApi.OrderResponse first = rec.firstResponse.get(i);
            DispatchApi.OrderResponse retry = rec.retryResponse.get(i);
            int newOnes = (first != null && first.status() == 201 ? 1 : 0) + (retry != null && retry.status() == 201 ? 1 : 0);
            created += Math.min(newOnes, 1);
            duplicates += Math.max(newOnes - 1, 0);
            if (retry != null) {
                retries++;
                // Exactly one of the two created the order, the other replayed it, and both name the same order.
                if (first != null && newOnes == 1 && first.orderId().equals(retry.orderId())
                        && (first.replayed() || retry.replayed())) {
                    retriesConsistent++;
                }
            }
        }
        Map<String, Object> orders = new LinkedHashMap<>();
        orders.put("created", created);
        orders.put("errors", rec.orderErrors.sum());
        orders.put("retried", retries);
        orders.put("retriesConsistent", retriesConsistent);
        orders.put("duplicatesCreated", duplicates);
        orders.put("offered", rec.firstOfferAt.size());
        orders.put("accepted", rec.acceptedAt.size());
        orders.put("delivered", rec.deliveredAt.size());

        Map<String, Object> couriers = new LinkedHashMap<>();
        couriers.put("offers", rec.offers.sum());
        couriers.put("offersResentOnReconnect", rec.offersResent.sum());
        couriers.put("accepts", rec.accepts.sum());
        couriers.put("declines", rec.declines.sum());
        couriers.put("expiredOrReleased", rec.expiredSeen.sum());
        couriers.put("answersRefused", rec.answersRefused.sum());
        couriers.put("answerRetries", rec.answerRetries.sum());
        couriers.put("answerRetriesRejected", rec.answerRetriesRejected.sum());
        couriers.put("socketDrops", rec.drops.sum());
        couriers.put("reconnects", rec.reconnects.sum());
        couriers.put("foundOfflineOnReconnect", rec.takenOffline.sum());
        couriers.put("socketErrors", rec.socketErrors.sum());
        couriers.put("sendsLostToDrops", rec.sendsLost.sum());
        couriers.put("deliveryErrors", rec.deliveryErrors.sum());

        if (options.dbUrl() != null) {
            orders.put("assignmentsByStatus", assignmentsByStatus());
        }
        return new Report(options.label(), runId, startedAt, scenarioInfo, replay, orders, couriers,
                LatencySummary.ofNanos(rec.sinceOrderSent(rec.firstOfferAt)), serverSideFirstOffer(),
                LatencySummary.ofNanos(rec.sinceOrderSent(rec.acceptedAt)), serverMetrics(), zoneIds, rec.notes());
    }

    /** The engine's own view, from Postgres: order {@code created_at} to its first {@code offered_at}. */
    private LatencySummary serverSideFirstOffer() {
        if (options.dbUrl() == null) {
            return null;
        }
        List<Long> nanos = new ArrayList<>();
        try (Connection db = DriverManager.getConnection(options.dbUrl());
             PreparedStatement q = db.prepareStatement("""
                     SELECT (EXTRACT(EPOCH FROM min(a.offered_at) - o.created_at) * 1e9)::bigint AS nanos
                       FROM orders o JOIN assignments a ON a.order_id = o.id
                      WHERE o.zone_id LIKE ?
                      GROUP BY o.id, o.created_at""")) {
            q.setString(1, runId + "-z%");
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    nanos.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            log("server-side latency unavailable: %s", e.getMessage());
            return null;
        }
        return LatencySummary.ofNanos(nanos.stream().mapToLong(Long::longValue).toArray());
    }

    /**
     * Every assignment the run made, by outcome, from Postgres. EXPIRED counts both timeouts and offers released
     * because their courier's socket stayed down past the grace (a disconnected courier never sees the release).
     */
    private Map<String, Long> assignmentsByStatus() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        try (Connection db = DriverManager.getConnection(options.dbUrl());
             PreparedStatement q = db.prepareStatement("""
                     SELECT a.status, count(*) FROM assignments a JOIN orders o ON o.id = a.order_id
                      WHERE o.zone_id LIKE ? GROUP BY a.status ORDER BY a.status""")) {
            q.setString(1, runId + "-z%");
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    byStatus.put(rs.getString(1), rs.getLong(2));
                }
            }
        } catch (SQLException e) {
            log("assignment counts unavailable: %s", e.getMessage());
        }
        return byStatus;
    }

    /** Engine pass counts and timings from {@code /actuator/metrics}; cumulative since the app started. */
    private Map<String, Object> serverMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            JsonNode pass = api.call("GET", "/actuator/metrics/dispatch.pass", null);
            JsonNode orders = api.call("GET", "/actuator/metrics/dispatch.pass.orders", null);
            double count = statistic(pass, "COUNT");
            m.put("passes", (long) count);
            m.put("passMeanMs", count == 0 ? 0 : round(statistic(pass, "TOTAL_TIME") * 1000 / count));
            m.put("ordersPerPass", count == 0 ? 0 : round(statistic(orders, "TOTAL") / count));
        } catch (RuntimeException e) {
            m.put("error", e.getMessage());
        }
        return m;
    }

    private static double statistic(JsonNode metric, String name) {
        for (JsonNode s : metric.path("measurements")) {
            if (s.path("statistic").asText().equals(name)) {
                return s.path("value").asDouble();
            }
        }
        return 0;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private void print(Report r) {
        log("orders: %s", r.orders());
        log("couriers: %s", r.couriers());
        log("replay: %s", r.replay());
        log("first offer (POST sent -> offer on a courier socket): %s", r.firstOffer().describe());
        if (r.firstOfferServer() != null) {
            log("first offer, server side (created_at -> offered_at): %s", r.firstOfferServer().describe());
        }
        log("accepted (POST sent -> accept acknowledged): %s", r.accepted().describe());
        log("server: %s", r.server());
        r.notes().forEach(n -> log("note: %s", n));
    }

    private void log(String format, Object... args) {
        System.out.printf("[sim %s] %s%n", options.label(), String.format(format, args));
    }
}
