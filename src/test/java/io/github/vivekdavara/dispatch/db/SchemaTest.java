package io.github.vivekdavara.dispatch.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs against the real Postgres (Flyway migrates it on startup). Each test runs in a transaction that is
 * rolled back, so tests don't see each other's rows. Each test triggers at most one constraint violation, as the
 * last statement, because Postgres aborts the transaction after an error.
 */
@SpringBootTest
@Transactional
class SchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void zone() {
        jdbc.update("INSERT INTO zones (id, name, center_lat, center_lng, radius_km) VALUES (?, ?, ?, ?, ?)",
                "test-zone", "Test zone", 42.35, -71.08, 3.0);
    }

    @Test
    void flywayAppliedV1() {
        String version = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(version).isEqualTo("1");
    }

    @Test
    void newOrderDefaultsToPendingStandard() {
        UUID id = order("key-1");
        var row = jdbc.queryForMap("SELECT status, tier FROM orders WHERE id = ?", id);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("tier", "STANDARD");
    }

    @Test
    void idempotencyKeyIsUnique() {
        order("same-key");
        assertThatThrownBy(() -> order("same-key")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void rejectsUnknownOrderStatus() {
        UUID id = order("key-1");
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'LOST' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOutOfRangeCoordinates() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO orders (id, idempotency_key, request_hash, zone_id,
                                    pickup_lat, pickup_lng, dropoff_lat, dropoff_lng)
                VALUES (?, 'bad', 'h', 'test-zone', 91, 0, 0, 0)""", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderCannotHaveTwoLiveAssignments() {
        UUID order = order("key-1");
        offer(order, courier("a"));
        UUID other = courier("b");
        assertThatThrownBy(() -> offer(order, other)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void courierCannotHaveTwoLiveAssignments() {
        UUID courier = courier("a");
        offer(order("key-1"), courier);
        UUID second = order("key-2");
        assertThatThrownBy(() -> offer(second, courier)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void declinedOfferFreesTheOrderForAnotherCourier() {
        UUID order = order("key-1");
        long first = offer(order, courier("a"));
        jdbc.update("UPDATE assignments SET status = 'DECLINED', responded_at = now() WHERE id = ?", first);
        offer(order, courier("b"));
        Integer attempts = jdbc.queryForObject("SELECT count(*) FROM assignments WHERE order_id = ?",
                Integer.class, order);
        assertThat(attempts).isEqualTo(2);
    }

    @Test
    void completedAssignmentFreesTheCourier() {
        UUID courier = courier("a");
        long first = offer(order("key-1"), courier);
        jdbc.update("UPDATE assignments SET status = 'COMPLETED', responded_at = now() WHERE id = ?", first);
        offer(order("key-2"), courier);
    }

    @Test
    void respondedAtMustBeSetExactlyWhenTheOfferIsAnswered() {
        long id = offer(order("key-1"), courier("a"));
        assertThatThrownBy(() -> jdbc.update("UPDATE assignments SET status = 'ACCEPTED' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID order(String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, idempotency_key, request_hash, zone_id,
                                    pickup_lat, pickup_lng, dropoff_lat, dropoff_lng)
                VALUES (?, ?, 'h', 'test-zone', 42.35, -71.08, 42.36, -71.06)""", id, key);
        return id;
    }

    private UUID courier(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO couriers (id, zone_id, name, status) VALUES (?, 'test-zone', ?, 'AVAILABLE')",
                id, name);
        return id;
    }

    private long offer(UUID order, UUID courier) {
        return jdbc.queryForObject(
                "INSERT INTO assignments (order_id, courier_id, distance_m) VALUES (?, ?, 100) RETURNING id",
                Long.class, order, courier);
    }
}
