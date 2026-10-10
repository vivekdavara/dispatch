package io.github.vivekdavara.dispatch.assignment;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.order.OrderStatus;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** The engine's reads and writes. Every state change is a compare-and-set on the row's current status. */
@Repository
public class AssignmentRepository {

    /**
     * The fields of a pending order the engine needs. {@code refusedBy} (couriers who declined it or let an offer
     * of it expire) is filled in by {@link #pendingWithRefusals} only; {@link #pendingOrders} leaves it empty.
     */
    public record PendingOrder(UUID id, OrderTier tier, Instant createdAt, GeoPoint pickup, Set<UUID> refusedBy) {
    }

    /** {@code created_at} minus the tier bonus: sorting by it is sorting by priority score (see pendingOrders). */
    private static final String SERVING_AT = "created_at - (CASE WHEN tier = 'PRIORITY' THEN ? ELSE 0 END)"
            + " * interval '1 minute'";

    private static final String SERVING_ORDER = " ORDER BY " + SERVING_AT + ", created_at, id LIMIT ?";

    /** Works in a SELECT from assignments and in an UPDATE's RETURNING; the zone comes from the order. */
    static final String COLUMNS = "id, order_id, courier_id, (SELECT o.zone_id FROM orders o WHERE o.id = order_id)"
            + " AS zone_id, status, distance_m, offered_at, responded_at";

    static final RowMapper<Assignment> ROW = (rs, n) -> {
        Timestamp responded = rs.getTimestamp("responded_at");
        return new Assignment(
                rs.getLong("id"),
                rs.getObject("order_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                rs.getString("zone_id"),
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
                         WHERE zone_id = ? AND status = 'PENDING'""" + SERVING_ORDER,
                (rs, n) -> new PendingOrder(
                        rs.getObject("id", UUID.class),
                        OrderTier.valueOf(rs.getString("tier")),
                        rs.getTimestamp("created_at").toInstant(),
                        new GeoPoint(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")),
                        Set.of()),
                zoneId, OrderTier.PRIORITY.bonusMinutes(), limit);
    }

    /**
     * {@link #pendingOrders} plus each order's refusals (the couriers who declined it or let an offer of it
     * expire), in the same query, so a pass can filter candidates without asking Postgres once per order.
     *
     * <p>Paged by keyset: with {@code after} null this is the first page; otherwise it's the pending orders that
     * come after order {@code after} in the serving order. That position is read from the order's own row, so it
     * still works if the order stopped being pending since the last page was read.
     */
    public List<PendingOrder> pendingWithRefusals(String zoneId, int limit, UUID after) {
        int bonus = OrderTier.PRIORITY.bonusMinutes();
        List<Object> args = new ArrayList<>();
        args.add(zoneId);
        String page = "";
        if (after != null) {
            page = " AND (" + SERVING_AT + ", created_at, id) > (SELECT " + SERVING_AT + ", created_at, id"
                    + " FROM orders prev WHERE prev.id = ?)";
            args.addAll(List.of(bonus, bonus, after));
        }
        args.addAll(List.of(bonus, limit));
        return jdbc.query("""
                        SELECT id, tier, created_at, pickup_lat, pickup_lng,
                               ARRAY(SELECT a.courier_id
                                       FROM assignments a
                                      WHERE a.order_id = orders.id AND a.status IN ('DECLINED', 'EXPIRED')) AS refused
                          FROM orders
                         WHERE zone_id = ? AND status = 'PENDING'""" + page + SERVING_ORDER,
                (rs, n) -> new PendingOrder(
                        rs.getObject("id", UUID.class),
                        OrderTier.valueOf(rs.getString("tier")),
                        rs.getTimestamp("created_at").toInstant(),
                        new GeoPoint(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")),
                        uuids(rs.getArray("refused"))),
                args.toArray());
    }

    /**
     * The zone's AVAILABLE couriers and when each became idle: one query per pass instead of one per order. As in
     * {@link #availableIdleSince}, a courier without {@code idle_since} counts as idle since its last update.
     */
    public Map<UUID, Instant> availableInZone(String zoneId) {
        Map<UUID, Instant> result = new HashMap<>();
        jdbc.query("""
                        SELECT id, coalesce(idle_since, updated_at) AS idle
                          FROM couriers
                         WHERE zone_id = ? AND status = 'AVAILABLE'""",
                rs -> {
                    result.put(rs.getObject("id", UUID.class), rs.getTimestamp("idle").toInstant());
                },
                zoneId);
        return result;
    }

    private static Set<UUID> uuids(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        try {
            return Set.copyOf(Arrays.asList((UUID[]) array.getArray()));
        } finally {
            array.free();
        }
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

    /** The courier's open offer, if any (the partial unique index allows at most one live assignment). */
    public Optional<Assignment> openOfferFor(UUID courierId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM assignments WHERE courier_id = ? AND status = 'OFFERED'",
                ROW, courierId).stream().findFirst();
    }

    /**
     * Up to {@code limit} offers still OFFERED that were made before {@code cutoff}, oldest first, leaving out the
     * ids in {@code skip}.
     */
    public List<Assignment> offeredBefore(Instant cutoff, List<Long> skip, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM assignments WHERE status = 'OFFERED' AND offered_at < ?"
                + " AND NOT (id = ANY (?)) ORDER BY offered_at LIMIT ?", ROW, Timestamp.from(cutoff),
                skip.toArray(Long[]::new), limit);
    }
}
