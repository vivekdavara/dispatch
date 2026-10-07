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

    public Optional<Zone> find(String id) {
        return jdbc.query("SELECT id, name, center_lat, center_lng, radius_km FROM zones WHERE id = ?", ROW, id)
                .stream().findFirst();
    }
}
