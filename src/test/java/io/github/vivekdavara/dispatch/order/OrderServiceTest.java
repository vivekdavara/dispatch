package io.github.vivekdavara.dispatch.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.order.CreateOrderRequest.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Against real Postgres; each test's rows are rolled back. */
@SpringBootTest
@Transactional
class OrderServiceTest {

    @Autowired
    OrderService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void zone() {
        // Back Bay, 3 km radius.
        jdbc.update("INSERT INTO zones (id, name, center_lat, center_lng, radius_km) VALUES (?, ?, ?, ?, ?)",
                "svc-zone", "Service test zone", 42.35, -71.08, 3.0);
    }

    static CreateOrderRequest request(double pickupLat, OrderTier tier) {
        return new CreateOrderRequest("svc-zone", new Location(pickupLat, -71.08), new Location(42.36, -71.06), tier);
    }

    @Test
    void createsAPendingOrder() {
        OrderService.Created c = service.create("k1", request(42.35, OrderTier.PRIORITY));
        assertThat(c.replayed()).isFalse();
        assertThat(c.order().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(c.order().tier()).isEqualTo(OrderTier.PRIORITY);
        assertThat(service.find(c.order().id())).contains(c.order());
    }

    @Test
    void retryWithSameKeyAndBodyReturnsTheSameOrder() {
        OrderService.Created first = service.create("k1", request(42.35, null));
        OrderService.Created retry = service.create("k1", request(42.35, OrderTier.STANDARD));
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.order().id()).isEqualTo(first.order().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE zone_id = 'svc-zone'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        service.create("k1", request(42.35, null));
        assertThatThrownBy(() -> service.create("k1", request(42.351, null)))
                .isInstanceOf(OrderService.IdempotencyKeyReusedException.class);
    }

    @Test
    void differentKeysCreateDifferentOrders() {
        var a = service.create("k1", request(42.35, null));
        var b = service.create("k2", request(42.35, null));
        assertThat(a.order().id()).isNotEqualTo(b.order().id());
    }

    @Test
    void unknownZoneIsRejected() {
        var r = new CreateOrderRequest("nowhere", new Location(42.35, -71.08), new Location(42.36, -71.06), null);
        assertThatThrownBy(() -> service.create("k1", r)).isInstanceOf(OrderService.UnknownZoneException.class);
    }

    @Test
    void pickupOutsideTheZoneIsRejected() {
        // 0.05 degrees of latitude is about 5.6 km north of the centre, outside the 3 km radius.
        assertThatThrownBy(() -> service.create("k1", request(42.40, null)))
                .isInstanceOf(OrderService.PickupOutsideZoneException.class);
    }
}
