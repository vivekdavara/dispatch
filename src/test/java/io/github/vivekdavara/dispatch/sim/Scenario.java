package io.github.vivekdavara.dispatch.sim;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * A synthetic workload, generated from a seed: orders and courier location pings (the events the simulator
 * replays), plus injected faults (courier sockets dropping). The same config always gives the same scenario, so a
 * before/after comparison replays exactly the same events.
 *
 * <ul>
 *   <li><b>Orders</b> arrive at a steady rate with a rush window where the rate is {@code rushFactor} times
 *       higher. Pickups are uniform over the inner 90% of a zone's disc, drop-offs within 3 km of the pickup, and
 *       a share are PRIORITY. A share are sent twice (a client retrying with the same Idempotency-Key).</li>
 *   <li><b>Pings</b>: every courier pings at a regular interval with jitter, from a random walk inside its zone
 *       (at most {@link #MAX_STEP_M} per ping).</li>
 *   <li><b>Socket drops</b>: a courier's socket goes away for a while. Short drops reconnect inside the server's
 *       reconnect grace; long ones don't. Pings keep coming during a drop (the app is alive, its socket isn't).</li>
 * </ul>
 */
public final class Scenario {

    /** Furthest a courier moves between two pings. */
    static final double MAX_STEP_M = 60.0;
    static final double METERS_PER_DEGREE_LAT = 111_195.0;

    /** Real neighbourhood centres, so positions look like a city; each zone is a 3 km disc. */
    static final List<GeoPoint> CENTRES = List.of(
            new GeoPoint(42.3503, -71.0810),  // Back Bay
            new GeoPoint(42.3736, -71.1097),  // Cambridge
            new GeoPoint(42.3467, -71.0405),  // Seaport
            new GeoPoint(42.3876, -71.0995),  // Somerville
            new GeoPoint(42.3097, -71.0868),  // Roxbury
            new GeoPoint(42.3290, -71.1080),  // Jamaica Plain
            new GeoPoint(42.3601, -71.0589),  // Downtown
            new GeoPoint(42.3505, -71.1054)); // Brookline border
    static final double ZONE_RADIUS_KM = 3.0;

    /**
     * @param seed           everything random derives from this
     * @param zones          how many zones (at most {@link #CENTRES}' size)
     * @param couriersPerZone couriers in each zone
     * @param orders         order events
     * @param pings          ping events
     * @param span           simulated time the events cover
     * @param priorityShare  share of orders that are PRIORITY
     * @param retryShare     share of orders sent a second time with the same key
     * @param rushStart      when the rush begins
     * @param rushLength     how long it lasts
     * @param rushFactor     how many times the base order rate it runs at
     * @param shortDrops     socket drops that come back within {@code shortDropMax}
     * @param shortDropMax   longest short drop
     * @param longDrops      socket drops that last between {@code longDropMin} and {@code longDropMax}
     */
    public record Config(long seed, int zones, int couriersPerZone, int orders, int pings, Duration span,
                         double priorityShare, double retryShare, Duration rushStart, Duration rushLength,
                         double rushFactor, int shortDrops, Duration shortDropMax, int longDrops,
                         Duration longDropMin, Duration longDropMax) {

        public Config {
            if (zones < 1 || zones > CENTRES.size()) {
                throw new IllegalArgumentException("zones must be 1.." + CENTRES.size());
            }
            if (couriersPerZone < 1 || orders < 0 || pings < 0) {
                throw new IllegalArgumentException("need at least one courier per zone and no negative counts");
            }
            if (rushStart.plus(rushLength).compareTo(span) > 0 || rushLength.compareTo(span) >= 0) {
                throw new IllegalArgumentException("the rush must fit inside the span, with time outside it");
            }
        }

        /**
         * The 50,000-event workload in the README: 10,000 orders and 40,000 pings over 5 simulated minutes,
         * 4 zones of 60 couriers, a 60 s rush at 3x the base order rate.
         */
        public static Config standard() {
            return new Config(20261009L, 4, 60, 10_000, 40_000, Duration.ofMinutes(5), 0.2, 0.05,
                    Duration.ofSeconds(120), Duration.ofSeconds(60), 3.0,
                    20, Duration.ofMillis(2_500), 20, Duration.ofSeconds(15), Duration.ofSeconds(30));
        }

        /**
         * A 1,000-event version for the CI smoke test and quick local checks: 200 orders and 800 pings over 20
         * simulated seconds in 2 zones of 8 couriers, with 3 short and 2 long socket drops.
         */
        public static Config small() {
            return new Config(7L, 2, 8, 200, 800, Duration.ofSeconds(20), 0.2, 0.1,
                    Duration.ofSeconds(8), Duration.ofSeconds(4), 3.0,
                    3, Duration.ofMillis(400), 2, Duration.ofSeconds(3), Duration.ofSeconds(4));
        }

        public int couriers() {
            return zones * couriersPerZone;
        }

        public int events() {
            return orders + pings;
        }
    }

    /** One replayed event, {@code atMs} simulated milliseconds after the start. */
    public sealed interface Event permits Ping, NewOrder {
        long atMs();
    }

    /** Courier number {@code courier} (0-based) reports its position. */
    public record Ping(long atMs, int courier, GeoPoint at) implements Event {
    }

    /** A customer order; if {@code retryAfterMs >= 0} the client sends it again that much later, same key. */
    public record NewOrder(long atMs, int index, int zone, GeoPoint pickup, GeoPoint dropoff, OrderTier tier,
                           long retryAfterMs) implements Event {
    }

    /** A fault: courier {@code courier}'s socket goes away at {@code atMs} for {@code downMs}. */
    public record SocketDrop(long atMs, int courier, long downMs) {
    }

    private final Config config;
    private final List<Event> events;
    private final List<SocketDrop> drops;

    private Scenario(Config config, List<Event> events, List<SocketDrop> drops) {
        this.config = config;
        this.events = List.copyOf(events);
        this.drops = List.copyOf(drops);
    }

    public Config config() {
        return config;
    }

    /** Orders and pings, in time order. */
    public List<Event> events() {
        return events;
    }

    /** Socket drops, in time order. */
    public List<SocketDrop> drops() {
        return drops;
    }

    /** The zone (0-based) courier {@code courier} works in. */
    public int zoneOf(int courier) {
        return courier / config.couriersPerZone();
    }

    public static GeoPoint centre(int zone) {
        return CENTRES.get(zone);
    }

    public static Scenario generate(Config c) {
        Random rnd = new Random(c.seed());
        List<Event> events = new ArrayList<>(c.events());
        long spanMs = c.span().toMillis();

        // Orders: the rush window holds rushFactor times the density of the rest of the span.
        long rushStart = c.rushStart().toMillis();
        long rushLength = c.rushLength().toMillis();
        double rushMass = rushLength * c.rushFactor();
        double inRush = rushMass / (spanMs - rushLength + rushMass);
        for (int i = 0; i < c.orders(); i++) {
            long at;
            if (rnd.nextDouble() < inRush) {
                at = rushStart + (long) (rnd.nextDouble() * rushLength);
            } else {
                long t = (long) (rnd.nextDouble() * (spanMs - rushLength));
                at = t < rushStart ? t : t + rushLength;
            }
            int zone = rnd.nextInt(c.zones());
            GeoPoint pickup = inDisc(rnd, centre(zone), ZONE_RADIUS_KM * 900);
            GeoPoint dropoff = inDisc(rnd, pickup, 3_000);
            OrderTier tier = rnd.nextDouble() < c.priorityShare() ? OrderTier.PRIORITY : OrderTier.STANDARD;
            long retry = rnd.nextDouble() < c.retryShare() ? 50 + rnd.nextInt(451) : -1;
            events.add(new NewOrder(at, i, zone, pickup, dropoff, tier, retry));
        }

        // Pings: spread evenly over couriers, each on its own phase, walking around its zone.
        int couriers = c.couriers();
        for (int courier = 0; courier < couriers; courier++) {
            int count = c.pings() / couriers + (courier < c.pings() % couriers ? 1 : 0);
            if (count == 0) {
                continue;
            }
            GeoPoint home = centre(courier / c.couriersPerZone());
            double interval = (double) spanMs / count;
            double phase = rnd.nextDouble() * interval;
            GeoPoint at = inDisc(rnd, home, ZONE_RADIUS_KM * 900);
            for (int k = 0; k < count; k++) {
                double jitter = (rnd.nextDouble() - 0.5) * 0.2 * interval;
                long t = Math.clamp((long) (phase + k * interval + jitter), 0, spanMs - 1);
                at = step(rnd, at, home);
                events.add(new Ping(t, courier, at));
            }
        }

        // Stable order for equal times: orders before pings, then by number, so the list is deterministic.
        events.sort(Comparator.comparingLong(Event::atMs)
                .thenComparingInt(e -> e instanceof NewOrder ? 0 : 1)
                .thenComparingInt(e -> e instanceof NewOrder o ? o.index() : ((Ping) e).courier()));

        List<SocketDrop> drops = new ArrayList<>();
        for (int i = 0; i < c.shortDrops() + c.longDrops(); i++) {
            long at = (long) (spanMs * (0.1 + 0.8 * rnd.nextDouble()));
            int courier = rnd.nextInt(couriers);
            long down = i < c.shortDrops()
                    ? 100 + (long) (rnd.nextDouble() * (c.shortDropMax().toMillis() - 100))
                    : c.longDropMin().toMillis()
                            + (long) (rnd.nextDouble() * (c.longDropMax().toMillis() - c.longDropMin().toMillis()));
            drops.add(new SocketDrop(at, courier, down));
        }
        drops.sort(Comparator.comparingLong(SocketDrop::atMs).thenComparingInt(SocketDrop::courier));
        return new Scenario(c, events, drops);
    }

    /** A point uniform over the disc of {@code radiusM} around {@code centre} (sqrt for uniform area). */
    static GeoPoint inDisc(Random rnd, GeoPoint centre, double radiusM) {
        double r = radiusM * Math.sqrt(rnd.nextDouble());
        double theta = 2 * Math.PI * rnd.nextDouble();
        return offset(centre, r * Math.cos(theta), r * Math.sin(theta));
    }

    /** One random-walk step that heads back toward the zone centre if it would leave the inner 95%. */
    static GeoPoint step(Random rnd, GeoPoint from, GeoPoint home) {
        double d = MAX_STEP_M * rnd.nextDouble();
        double theta = 2 * Math.PI * rnd.nextDouble();
        GeoPoint next = offset(from, d * Math.cos(theta), d * Math.sin(theta));
        if (next.distanceMetersTo(home) > ZONE_RADIUS_KM * 950) {
            double back = Math.min(d, from.distanceMetersTo(home));
            double north = (home.lat() - from.lat()) * METERS_PER_DEGREE_LAT;
            double east = (home.lng() - from.lng()) * METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(from.lat()));
            double norm = Math.hypot(north, east);
            return norm == 0 ? from : offset(from, back * north / norm, back * east / norm);
        }
        return next;
    }

    static GeoPoint offset(GeoPoint p, double northM, double eastM) {
        double lat = p.lat() + northM / METERS_PER_DEGREE_LAT;
        double lng = p.lng() + eastM / (METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(p.lat())));
        return new GeoPoint(lat, lng);
    }
}
