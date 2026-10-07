package io.github.vivekdavara.dispatch.courier;

import io.github.vivekdavara.dispatch.config.DispatchProperties;
import io.github.vivekdavara.dispatch.domain.GeoPoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.DistanceUnit;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

/**
 * Live courier positions in Redis. Postgres is never touched on this path: pings arrive far more often than
 * orders.
 *
 * <ul>
 *   <li>{@code couriers:geo:{zoneId}}: a GEO set (a sorted set keyed by geohash) of courier id to last position.</li>
 *   <li>{@code courier:seen:{courierId}}: exists while the last ping is fresh; its TTL is the freshness window.</li>
 * </ul>
 *
 * A courier whose seen key has expired is skipped by {@link #freshWithin} and removed from the GEO set then, so
 * disconnected couriers drop out without a sweeper. That removal isn't atomic with the check: a ping landing in
 * between can be removed, and the courier's next ping (every few seconds) puts it back. Positions are soft state,
 * so that's an acceptable trade for not needing a Lua script.
 */
@Component
public class CourierLocations {

    /** A courier near the search point, with its last reported position. */
    public record Nearby(UUID courierId, GeoPoint location) {
    }

    private final StringRedisTemplate redis;
    private final Duration freshFor;

    public CourierLocations(StringRedisTemplate redis, DispatchProperties props) {
        this.redis = redis;
        this.freshFor = props.locations().freshFor();
    }

    public static String geoKey(String zoneId) {
        return "couriers:geo:" + zoneId;
    }

    public static String seenKey(UUID courierId) {
        return "courier:seen:" + courierId;
    }

    /** Records a ping: the new position and a fresh seen marker, pipelined into one round trip. */
    public void update(String zoneId, UUID courierId, GeoPoint at) {
        String member = courierId.toString();
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                ops.opsForGeo().add(geoKey(zoneId), new Point(at.lng(), at.lat()), member);
                ops.opsForValue().set(seenKey(courierId), Long.toString(System.currentTimeMillis()), freshFor);
                return null;
            }
        });
    }

    /** Forgets a courier's position (it went offline). */
    public void remove(String zoneId, UUID courierId) {
        redis.opsForGeo().remove(geoKey(zoneId), courierId.toString());
        redis.delete(seenKey(courierId));
    }

    /**
     * Couriers in the zone with a fresh ping within {@code radiusMeters} of {@code center}, nearest first.
     * Membership here says nothing about status: callers still check AVAILABLE in Postgres.
     */
    public List<Nearby> freshWithin(String zoneId, GeoPoint center, double radiusMeters) {
        var results = redis.opsForGeo().search(
                geoKey(zoneId),
                GeoReference.fromCoordinate(center.lng(), center.lat()),
                new Distance(radiusMeters, DistanceUnit.METERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeCoordinates().sortAscending());
        if (results == null || results.getContent().isEmpty()) {
            return List.of();
        }
        List<GeoResult<GeoLocation<String>>> hits = results.getContent();
        List<String> seenKeys = hits.stream().map(h -> seenKey(UUID.fromString(h.getContent().getName()))).toList();
        List<String> seen = redis.opsForValue().multiGet(seenKeys);

        List<Nearby> fresh = new ArrayList<>(hits.size());
        List<String> stale = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            GeoLocation<String> loc = hits.get(i).getContent();
            if (seen != null && seen.get(i) != null) {
                Point p = loc.getPoint();
                fresh.add(new Nearby(UUID.fromString(loc.getName()), new GeoPoint(p.getY(), p.getX())));
            } else {
                stale.add(loc.getName());
            }
        }
        if (!stale.isEmpty()) {
            redis.opsForGeo().remove(geoKey(zoneId), stale.toArray(String[]::new));
        }
        return fresh;
    }
}
