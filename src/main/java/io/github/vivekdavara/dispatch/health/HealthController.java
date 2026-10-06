package io.github.vivekdavara.dispatch.health;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A small health check for load balancers and the simulator: pings Postgres and Redis and reports each one's
 * status and round-trip time. Returns 200 when both are up and 503 otherwise. Spring's richer
 * {@code /actuator/health} is also exposed.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public HealthController(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public record Check(String status, Long latencyMs, String error) {
    }

    public record Health(String status, Map<String, Check> checks) {
    }

    @GetMapping
    public ResponseEntity<Health> health() {
        Map<String, Check> checks = new LinkedHashMap<>();
        checks.put("postgres", check(() -> jdbc.queryForObject("SELECT 1", Integer.class) == 1));
        checks.put("redis", check(() -> "PONG".equals(redis.execute(c -> c.ping(), true))));
        boolean up = checks.values().stream().allMatch(c -> c.status().equals("UP"));
        HttpStatus code = up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(code).body(new Health(up ? "UP" : "DOWN", checks));
    }

    private static Check check(Supplier<Boolean> probe) {
        long start = System.nanoTime();
        try {
            boolean ok = probe.get();
            long ms = (System.nanoTime() - start) / 1_000_000;
            return ok ? new Check("UP", ms, null) : new Check("DOWN", ms, "unexpected response");
        } catch (RuntimeException e) {
            return new Check("DOWN", null, e.getClass().getSimpleName());
        }
    }
}
