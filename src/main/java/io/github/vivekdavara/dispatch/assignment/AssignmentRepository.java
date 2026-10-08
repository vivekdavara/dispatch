package io.github.vivekdavara.dispatch.assignment;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.order.OrderStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** The engine's reads and writes. Every state change is a compare-and-set on the row's current status. */
@Repository
public class AssignmentRepository {

    /** The fields of a pending order the engine needs. */
    public record PendingOrder(UUID id, OrderTier tier, Instant createdAt, GeoPoint pickup) {
    }

    static final String COLUMNS = "id, order_id, courier_id, status, distance_m, offered_at, responded_at";

    static final RowMapper<Assignment> ROW = (rs, n) -> {
        Timestamp responded = rs.getTimestamp("responded_at");
        return new Assignment(
                rs.getLong("id"),
                rs.getObject("order_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                AssignmentStatus.valueOf(rs.getString("status")),
                rs.getDouble("distance_m"),
                rs.getTimestamp("offered_at").toInstant(),
                responded == null ? null : responded.toInstant());
    };

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
     * Which of {@code courierIds} can be offered {@code orderId} right now, with when they became idle: AVAILABLE
     * in the zone, and not one who already declined this order or let an offer of it expire. A courier marked
     * AVAILABLE without {@code idle_since} (inserted directly) counts as idle since its last update.
     */
    public Map<UUID, Instant> availableIdleSince(String zoneId, UUID orderId, List<UUID> courierIds) {
        Map<UUID, Instant> result = new HashMap<>();
        if (courierIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                        SELECT c.id, coalesce(c.idle_since, c.updated_at) AS idle
                          FROM couriers c
                         WHERE c.zone_id = ? AND c.status = 'AVAILABLE' AND c.id = ANY (?)
                           AND NOT EXISTS (SELECT 1
                                             FROM assignments a
                                            WHERE a.order_id = ? AND a.courier_id = c.id
                                              AND a.status IN ('DECLINED', 'EXPIRED'))""",
                rs -> {
                    result.put(rs.getObject("id", UUID.class), rs.getTimestamp("idle").toInstant());
                },
                zoneId, courierIds.toArray(UUID[]::new), orderId);
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

    public Optional<Assignment> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM assignments WHERE id = ?", ROW, id).stream().findFirst();
    }

    /**
     * Compare-and-set on an assignment: from {@code from} to {@code to}, only if it is still in {@code from} and
     * belongs to {@code courierId}. Leaving OFFERED stamps {@code responded_at} (the schema requires it).
     */
    public Optional<Assignment> transition(long id, UUID courierId, AssignmentStatus from, AssignmentStatus to,
                                           Instant now) {
        return jdbc.query("""
                        UPDATE assignments
                           SET status = ?,
                               responded_at = coalesce(responded_at, ?)
                         WHERE id = ? AND courier_id = ? AND status = ?
                        """ + "RETURNING " + COLUMNS,
                ROW, to.name(), Timestamp.from(now), id, courierId, from.name()).stream().findFirst();
    }

    /** Compare-and-set on an order's status. */
    public boolean moveOrder(UUID orderId, OrderStatus from, OrderStatus to, Instant now) {
        return jdbc.update("UPDATE orders SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                to.name(), Timestamp.from(now), orderId, from.name()) == 1;
    }

    /** Up to {@code limit} offers still OFFERED that were made before {@code cutoff}, oldest first. */
    public List<Assignment> offeredBefore(Instant cutoff, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM assignments WHERE status = 'OFFERED' AND offered_at < ?"
                + " ORDER BY offered_at LIMIT ?", ROW, Timestamp.from(cutoff), limit);
    }
}
