package io.github.vivekdavara.dispatch.order;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    static final String COLUMNS = """
            id, zone_id, pickup_lat, pickup_lng, dropoff_lat, dropoff_lng, tier, status, created_at, updated_at""";

    static final RowMapper<Order> ROW = (rs, n) -> new Order(
            rs.getObject("id", UUID.class),
            rs.getString("zone_id"),
            new GeoPoint(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")),
            new GeoPoint(rs.getDouble("dropoff_lat"), rs.getDouble("dropoff_lng")),
            OrderTier.valueOf(rs.getString("tier")),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    /** An existing order with the request hash it was created from. */
    public record Stored(Order order, String requestHash) {
    }

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the order unless one with the same idempotency key exists. Returns the new order, or empty when the
     * key was taken (including by a concurrent request that won the race): {@code ON CONFLICT DO NOTHING} makes
     * that a normal outcome instead of an exception that would abort the transaction.
     */
    public Optional<Order> insertIfAbsent(UUID id, String idempotencyKey, String requestHash,
                                          CreateOrderRequest r, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.query("""
                        INSERT INTO orders (id, idempotency_key, request_hash, zone_id, pickup_lat, pickup_lng,
                                            dropoff_lat, dropoff_lng, tier, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """ + "RETURNING " + COLUMNS,
                ROW,
                id, idempotencyKey, requestHash, r.zoneId(), r.pickup().lat(), r.pickup().lng(),
                r.dropoff().lat(), r.dropoff().lng(), r.effectiveTier().name(), ts, ts)
                .stream().findFirst();
    }

    public Optional<Stored> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT " + COLUMNS + ", request_hash FROM orders WHERE idempotency_key = ?",
                (rs, n) -> new Stored(ROW.mapRow(rs, n), rs.getString("request_hash")), idempotencyKey)
                .stream().findFirst();
    }

    public Optional<Order> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE id = ?", ROW, id).stream().findFirst();
    }
}
