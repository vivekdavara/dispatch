package io.github.vivekdavara.dispatch.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A zone with a unique id for tests that must commit (concurrency tests can't run inside one rolled-back
 * transaction). {@link #delete()} removes the zone and everything in it.
 */
public final class TestZone {

    public static final double CENTER_LAT = 42.35;
    public static final double CENTER_LNG = -71.08;

    private final JdbcTemplate jdbc;
    private final String id;

    private TestZone(JdbcTemplate jdbc, String id) {
        this.jdbc = jdbc;
        this.id = id;
    }

    /** Creates a 3 km zone centred on Back Bay, Boston. */
    public static TestZone create(JdbcTemplate jdbc, String prefix) {
        String id = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO zones (id, name, center_lat, center_lng, radius_km) VALUES (?, ?, ?, ?, 3.0)",
                id, id, CENTER_LAT, CENTER_LNG);
        return new TestZone(jdbc, id);
    }

    public String id() {
        return id;
    }

    public void delete() {
        jdbc.update("DELETE FROM assignments WHERE order_id IN (SELECT id FROM orders WHERE zone_id = ?)", id);
        jdbc.update("DELETE FROM assignments WHERE courier_id IN (SELECT id FROM couriers WHERE zone_id = ?)", id);
        jdbc.update("DELETE FROM orders WHERE zone_id = ?", id);
        jdbc.update("DELETE FROM couriers WHERE zone_id = ?", id);
        jdbc.update("DELETE FROM zones WHERE id = ?", id);
    }
}
