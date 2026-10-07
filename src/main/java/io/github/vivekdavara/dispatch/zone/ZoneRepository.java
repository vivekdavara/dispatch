package io.github.vivekdavara.dispatch.zone;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ZoneRepository {

    private static final RowMapper<Zone> ROW = (rs, n) -> new Zone(
            rs.getString("id"),
            rs.getString("name"),
            new GeoPoint(rs.getDouble("center_lat"), rs.getDouble("center_lng")),
            rs.getDouble("radius_km"));

    private final JdbcTemplate jdbc;

    public ZoneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the zone or replaces its name, centre and radius. */
    public Zone upsert(Zone z) {
        jdbc.update("""
                INSERT INTO zones (id, name, center_lat, center_lng, radius_km) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE
                   SET name = excluded.name, center_lat = excluded.center_lat,
                       center_lng = excluded.center_lng, radius_km = excluded.radius_km""",
                z.id(), z.name(), z.center().lat(), z.center().lng(), z.radiusKm());
        return z;
    }

    public Optional<Zone> find(String id) {
        return jdbc.query("SELECT id, name, center_lat, center_lng, radius_km FROM zones WHERE id = ?", ROW, id)
                .stream().findFirst();
    }
}
