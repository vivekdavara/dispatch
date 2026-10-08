package io.github.vivekdavara.dispatch.support;

import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Committed test data in one {@link TestZone}: couriers (Postgres row plus Redis ping) and orders with chosen
 * ages. {@link #cleanUp()} removes all of it from both stores.
 */
public final class Fixtures {

    public static final GeoPoint CENTER = new GeoPoint(TestZone.CENTER_LAT, TestZone.CENTER_LNG);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final CourierLocations locations;
    private final TestZone zone;
    private final List<UUID> couriers = new ArrayList<>();

    public Fixtures(JdbcTemplate jdbc, StringRedisTemplate redis, CourierLocations locations, String prefix) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.locations = locations;
        this.zone = TestZone.create(jdbc, prefix);
    }

    public String zoneId() {
        return zone.id();
    }

    /** A point {@code meters} north of the zone centre (one degree of latitude is about 111.2 km). */
    public static GeoPoint north(double meters) {
        return new GeoPoint(CENTER.lat() + meters / 111_195.0, CENTER.lng());
    }

    /** An AVAILABLE courier idle for {@code idleFor}, with a fresh ping at {@code at}. */
    public UUID availableCourier(GeoPoint at, Duration idleFor) {
        UUID id = courier("AVAILABLE", idleFor);
        locations.update(zone.id(), id, at);
        return id;
    }

    /** A courier row in {@code status} with no ping. */
    public UUID courier(String status, Duration idleFor) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO couriers (id, zone_id, name, status, idle_since) VALUES (?, ?, ?, ?, ?)",
                id, zone.id(), "courier-" + couriers.size(), status, Timestamp.from(Instant.now().minus(idleFor)));
        couriers.add(id);
        return id;
    }

    public void ping(UUID courierId, GeoPoint at) {
        locations.update(zone.id(), courierId, at);
    }

    /** A PENDING order at the zone centre created {@code age} ago. */
    public UUID order(OrderTier tier, Duration age) {
        return order(tier, age, CENTER);
    }

    public UUID order(OrderTier tier, Duration age, GeoPoint pickup) {
        UUID id = UUID.randomUUID();
        Timestamp created = Timestamp.from(Instant.now().minus(age));
        jdbc.update("""
                INSERT INTO orders (id, idempotency_key, request_hash, zone_id, pickup_lat, pickup_lng,
                                    dropoff_lat, dropoff_lng, tier, created_at, updated_at)
                VALUES (?, ?, 'h', ?, ?, ?, 42.36, -71.06, ?, ?, ?)""",
                id, "fixture-" + id, zone.id(), pickup.lat(), pickup.lng(), tier.name(), created, created);
        return id;
    }

    public String orderStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
    }

    public String courierStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM couriers WHERE id = ?", String.class, id);
    }

    public String assignmentStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM assignments WHERE id = ?", String.class, id);
    }

    public Instant idleSince(UUID courierId) {
        return jdbc.queryForObject("SELECT idle_since FROM couriers WHERE id = ?", Timestamp.class, courierId)
                .toInstant();
    }

    public void cleanUp() {
        redis.delete(CourierLocations.geoKey(zone.id()));
        couriers.forEach(c -> redis.delete(CourierLocations.seenKey(c)));
        zone.delete();
    }
}
