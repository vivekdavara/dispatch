package io.github.vivekdavara.dispatch.assignment;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The engine's reads and writes. Every state change is a compare-and-set on the row's current status. */
@Repository
public class AssignmentRepository {

    /** The fields of a pending order the engine needs. */
    public record PendingOrder(UUID id, OrderTier tier, Instant createdAt, GeoPoint pickup) {
    }

    private final JdbcTemplate jdbc;

    public AssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Up to {@code limit} of the zone's pending orders, highest priority first. Ordering by {@code created_at}
     * minus the tier bonus is the same order as {@code PriorityScore} (score = bonus + time waited), so the batch
     * can't miss a newer priority order that outranks old standard ones. Needs no clock.
     */
    public List<PendingOrder> pendingOrders(String zoneId, int limit) {
        return jdbc.query("""
                        SELECT id, tier, created_at, pickup_lat, pickup_lng
                          FROM orders
                         WHERE zone_id = ? AND status = 'PENDING'
                         ORDER BY created_at - (CASE WHEN tier = 'PRIORITY' THEN ? ELSE 0 END) * interval '1 minute',
                                  created_at, id
                         LIMIT ?""",
                (rs, n) -> new PendingOrder(
                        rs.getObject("id", UUID.class),
                        OrderTier.valueOf(rs.getString("tier")),
                        rs.getTimestamp("created_at").toInstant(),
                        new GeoPoint(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng"))),
                zoneId, OrderTier.PRIORITY.bonusMinutes(), limit);
    }

    /**
     * Which of {@code courierIds} are AVAILABLE in the zone right now, with when they became idle. A courier
     * marked AVAILABLE without {@code idle_since} (inserted directly) counts as idle since its last update.
     */
    public Map<UUID, Instant> availableIdleSince(String zoneId, List<UUID> courierIds) {
        Map<UUID, Instant> result = new HashMap<>();
        if (courierIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                        SELECT id, coalesce(idle_since, updated_at) AS idle
                          FROM couriers
                         WHERE zone_id = ? AND status = 'AVAILABLE' AND id = ANY (?)""",
                rs -> {
                    result.put(rs.getObject("id", UUID.class), rs.getTimestamp("idle").toInstant());
                },
                zoneId, courierIds.toArray(UUID[]::new));
        return result;
    }

    /** PENDING to OFFERED, only if the order is still PENDING. */
    public boolean markOrderOffered(UUID orderId, Instant now) {
        return jdbc.update("UPDATE orders SET status = 'OFFERED', updated_at = ? WHERE id = ? AND status = 'PENDING'",
                Timestamp.from(now), orderId) == 1;
    }

    /** AVAILABLE to OFFERED, only if the courier is still AVAILABLE. */
    public boolean markCourierOffered(UUID courierId, Instant now) {
        return jdbc.update(
                "UPDATE couriers SET status = 'OFFERED', updated_at = ? WHERE id = ? AND status = 'AVAILABLE'",
                Timestamp.from(now), courierId) == 1;
    }

    public long insertOffer(UUID orderId, UUID courierId, double distanceMeters, Instant now) {
        return jdbc.queryForObject("""
                        INSERT INTO assignments (order_id, courier_id, status, distance_m, offered_at)
                        VALUES (?, ?, 'OFFERED', ?, ?)
                        RETURNING id""",
                Long.class, orderId, courierId, distanceMeters, Timestamp.from(now));
    }
}
