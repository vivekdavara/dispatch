package io.github.vivekdavara.dispatch.courier;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CourierRepository {

    static final String COLUMNS = "id, zone_id, name, status, idle_since, created_at, updated_at";

    static final RowMapper<Courier> ROW = (rs, n) -> {
        Timestamp idle = rs.getTimestamp("idle_since");
        return new Courier(
                rs.getObject("id", UUID.class),
                rs.getString("zone_id"),
                rs.getString("name"),
                CourierStatus.valueOf(rs.getString("status")),
                idle == null ? null : idle.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    };

    private final JdbcTemplate jdbc;

    public CourierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Registers a courier; new couriers start OFFLINE. */
    public Courier insert(UUID id, String zoneId, String name, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.queryForObject("""
                        INSERT INTO couriers (id, zone_id, name, status, created_at, updated_at)
                        VALUES (?, ?, ?, 'OFFLINE', ?, ?)
                        """ + "RETURNING " + COLUMNS,
                ROW, id, zoneId, name, ts, ts);
    }

    public Optional<Courier> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM couriers WHERE id = ?", ROW, id).stream().findFirst();
    }

    /**
     * Compare-and-set on status: moves the courier from {@code from} to {@code to} only if it is currently in
     * {@code from}. Returns false if it wasn't (someone else changed it first). Becoming AVAILABLE resets
     * {@code idle_since}, which the fairness tie-break reads.
     */
    public boolean transition(UUID id, CourierStatus from, CourierStatus to, Instant now) {
        Timestamp ts = Timestamp.from(now);
        int updated = jdbc.update("""
                UPDATE couriers
                   SET status = ?,
                       idle_since = CASE WHEN ? = 'AVAILABLE' THEN ? ELSE idle_since END,
                       updated_at = ?
                 WHERE id = ? AND status = ?""",
                to.name(), to.name(), ts, ts, id, from.name());
        return updated == 1;
    }
}
