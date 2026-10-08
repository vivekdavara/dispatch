package io.github.vivekdavara.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.courier.CourierService;
import io.github.vivekdavara.dispatch.courier.CourierStatus;
import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.order.CreateOrderRequest;
import io.github.vivekdavara.dispatch.order.OrderService;
import io.github.vivekdavara.dispatch.support.Fixtures;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The loop end to end: nothing here calls the engine. Orders, status changes, declines and timeouts alone must
 * get orders offered. Short timeouts keep it fast; the context is closed afterwards so its background passes
 * can't touch other test classes' data.
 */
@SpringBootTest(properties = {
        "dispatch.loop.enabled=true",
        "dispatch.offers.timeout=1s",
        "dispatch.loop.expiry-interval=100ms",
        "dispatch.loop.sweep-interval=300ms"})
@DirtiesContext
class DispatchLoopTest {

    static final Duration MINUTE = Duration.ofMinutes(1);

    @Autowired
    OrderService orders;

    @Autowired
    CourierService couriers;

    @Autowired
    OfferService offers;

    @Autowired
    AssignmentRepository assignments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CourierLocations locations;

    Fixtures f;

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc, redis, locations, "loop");
    }

    @AfterEach
    void tearDown() {
        f.cleanUp();
    }

    UUID createOrder() {
        GeoPoint c = Fixtures.CENTER;
        var request = new CreateOrderRequest(f.zoneId(), new CreateOrderRequest.Location(c.lat(), c.lng()),
                new CreateOrderRequest.Location(42.36, -71.06), null);
        return orders.create(UUID.randomUUID().toString(), request).order().id();
    }

    /** The order's live (OFFERED) assignment to anyone but {@code notCourier}, once there is one. */
    Assignment awaitOffer(UUID orderId, UUID notCourier) {
        return await("an offer for " + orderId, () -> jdbc.queryForList(
                        "SELECT id FROM assignments WHERE order_id = ? AND status = 'OFFERED'", Long.class, orderId)
                .stream()
                .flatMap(id -> assignments.find(id).stream())
                .filter(a -> !a.courierId().equals(notCourier))
                .findFirst()
                .orElse(null));
    }

    static <T> T await(String what, Supplier<T> probe) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            T value = probe.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    void aNewOrderIsOfferedWithoutAnyoneAskingForAPass() {
        UUID courier = f.availableCourier(Fixtures.north(300), MINUTE);

        UUID order = createOrder();

        assertThat(awaitOffer(order, null).courierId()).isEqualTo(courier);
    }

    @Test
    void aDeclinedOrderGoesStraightToTheNextCourier() {
        UUID near = f.availableCourier(Fixtures.north(200), MINUTE);
        UUID far = f.availableCourier(Fixtures.north(1_000), MINUTE);
        UUID order = createOrder();
        Assignment first = awaitOffer(order, null);
        assertThat(first.courierId()).isEqualTo(near);

        offers.decline(first.id(), near);

        assertThat(awaitOffer(order, near).courierId()).isEqualTo(far);
    }

    @Test
    void anUnansweredOfferTimesOutAndIsReoffered() {
        UUID near = f.availableCourier(Fixtures.north(200), MINUTE);
        UUID far = f.availableCourier(Fixtures.north(1_000), MINUTE);
        UUID order = createOrder();
        Assignment first = awaitOffer(order, null);

        Assignment second = awaitOffer(order, near); // nobody answers the first one; timeout is 1 s here

        assertThat(second.courierId()).isEqualTo(far);
        assertThat(f.assignmentStatus(first.id())).isEqualTo("EXPIRED");
        assertThat(f.courierStatus(near)).isEqualTo("AVAILABLE");
    }

    @Test
    void aCourierComingOnlinePicksUpTheWaitingOrder() {
        UUID courier = couriers.register(f.zoneId(), "Late Larry").id();
        couriers.updateLocation(courier, Fixtures.north(400));
        UUID order = createOrder();
        assertThat(f.orderStatus(order)).isEqualTo("PENDING");

        couriers.setStatus(courier, CourierStatus.AVAILABLE);

        assertThat(awaitOffer(order, null).courierId()).isEqualTo(courier);
        redis.delete(CourierLocations.seenKey(courier));
    }

    @Test
    void theSweepCatchesACourierWhoDroveIntoRange() {
        UUID courier = f.availableCourier(Fixtures.north(8_000), MINUTE); // outside the 5 km pickup radius
        UUID order = createOrder();
        assertThat(f.orderStatus(order)).isEqualTo("PENDING");

        f.ping(courier, Fixtures.north(1_000)); // a ping sends no event; only the sweep can notice

        assertThat(awaitOffer(order, null).courierId()).isEqualTo(courier);
    }

    @Test
    void aFinishedDeliveryFreesTheCourierForTheNextOrder() {
        UUID courier = f.availableCourier(Fixtures.north(300), MINUTE);
        UUID first = createOrder();
        Assignment a = awaitOffer(first, null);
        UUID second = createOrder();
        offers.accept(a.id(), courier);
        offers.pickedUp(a.id(), courier);

        offers.delivered(a.id(), courier);

        assertThat(awaitOffer(second, null).courierId()).isEqualTo(courier);
        assertThat(List.of(f.orderStatus(first))).containsExactly("DELIVERED");
    }
}
