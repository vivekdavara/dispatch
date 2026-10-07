package io.github.vivekdavara.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.support.Fixtures;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** The engine end to end against real Postgres and Redis, with committed data in an isolated zone. */
@SpringBootTest
class AssignmentEngineTest {

    static final Duration MINUTE = Duration.ofMinutes(1);

    @Autowired
    AssignmentEngine engine;

    @Autowired
    AssignmentRepository repo;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CourierLocations locations;

    Fixtures f;

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc, redis, locations, "engine");
    }

    @AfterEach
    void tearDown() {
        f.cleanUp();
    }

    @Test
    void offersTheOrderToTheNearestAvailableCourier() {
        UUID near = f.availableCourier(Fixtures.north(300), MINUTE);
        f.availableCourier(Fixtures.north(1_200), MINUTE.multipliedBy(30));
        UUID order = f.order(OrderTier.STANDARD, MINUTE);

        List<Offer> offers = engine.dispatchZone(f.zoneId());

        assertThat(offers).hasSize(1);
        Offer o = offers.get(0);
        assertThat(o.orderId()).isEqualTo(order);
        assertThat(o.courierId()).isEqualTo(near);
        assertThat(o.distanceMeters()).isCloseTo(300, within(2.0));
        assertThat(f.orderStatus(order)).isEqualTo("OFFERED");
        assertThat(f.courierStatus(near)).isEqualTo("OFFERED");
        assertThat(jdbc.queryForMap("SELECT status, courier_id FROM assignments WHERE id = ?", o.assignmentId()))
                .containsEntry("status", "OFFERED").containsEntry("courier_id", near);
    }

    @Test
    void withinFiftyMetresTheLongestIdleCourierWins() {
        f.availableCourier(Fixtures.north(200), MINUTE);
        UUID idleLonger = f.availableCourier(Fixtures.north(230), MINUTE.multipliedBy(20));
        f.order(OrderTier.STANDARD, MINUTE);

        assertThat(engine.dispatchZone(f.zoneId())).extracting(Offer::courierId).containsExactly(idleLonger);
    }

    @Test
    void couriersWithoutAFreshPingAreSkipped() {
        UUID stale = f.availableCourier(Fixtures.north(100), MINUTE);
        UUID fresh = f.availableCourier(Fixtures.north(900), MINUTE);
        redis.delete(CourierLocations.seenKey(stale)); // its TTL ran out
        f.order(OrderTier.STANDARD, MINUTE);

        assertThat(engine.dispatchZone(f.zoneId())).extracting(Offer::courierId).containsExactly(fresh);
    }

    @Test
    void offlineBusyAndOfferedCouriersAreSkippedEvenWithAPing() {
        for (String status : List.of("OFFLINE", "BUSY", "OFFERED")) {
            f.ping(f.courier(status, MINUTE), Fixtures.north(50));
        }
        UUID available = f.availableCourier(Fixtures.north(2_000), MINUTE);
        f.order(OrderTier.STANDARD, MINUTE);

        assertThat(engine.dispatchZone(f.zoneId())).extracting(Offer::courierId).containsExactly(available);
    }

    @Test
    void noCourierWithinFiveKilometresLeavesTheOrderPending() {
        UUID far = f.availableCourier(Fixtures.north(5_200), MINUTE);
        UUID order = f.order(OrderTier.STANDARD, MINUTE);

        assertThat(engine.dispatchZone(f.zoneId())).isEmpty();
        assertThat(f.orderStatus(order)).isEqualTo("PENDING");
        assertThat(f.courierStatus(far)).isEqualTo("AVAILABLE");
    }

    @Test
    void aPriorityOrderOutranksAStandardOneThatWaitedLess() {
        f.availableCourier(Fixtures.north(300), MINUTE);
        UUID standard = f.order(OrderTier.STANDARD, MINUTE.multipliedBy(5)); // score 5
        UUID priority = f.order(OrderTier.PRIORITY, MINUTE);                 // score 11

        assertThat(engine.dispatchZone(f.zoneId())).extracting(Offer::orderId).containsExactly(priority);
        assertThat(f.orderStatus(standard)).isEqualTo("PENDING");
    }

    @Test
    void aStandardOrderThatWaitedLongEnoughOutranksANewPriorityOne() {
        f.availableCourier(Fixtures.north(300), MINUTE);
        UUID standard = f.order(OrderTier.STANDARD, MINUTE.multipliedBy(15)); // score 15
        f.order(OrderTier.PRIORITY, Duration.ZERO);                            // score 10

        assertThat(engine.dispatchZone(f.zoneId())).extracting(Offer::orderId).containsExactly(standard);
    }

    @Test
    void eachCourierGetsAtMostOneOrderPerPass() {
        UUID a = f.availableCourier(Fixtures.north(100), MINUTE);
        UUID b = f.availableCourier(Fixtures.north(400), MINUTE);
        f.order(OrderTier.STANDARD, MINUTE.multipliedBy(3));
        f.order(OrderTier.STANDARD, MINUTE.multipliedBy(2));
        UUID third = f.order(OrderTier.STANDARD, MINUTE);

        List<Offer> offers = engine.dispatchZone(f.zoneId());

        assertThat(offers).extracting(Offer::courierId).containsExactly(a, b);
        assertThat(f.orderStatus(third)).isEqualTo("PENDING");
    }

    @Test
    void aSecondPassFindsNothingLeftToDo() {
        f.availableCourier(Fixtures.north(100), MINUTE);
        f.order(OrderTier.STANDARD, MINUTE);
        f.order(OrderTier.STANDARD, MINUTE);

        assertThat(engine.dispatchZone(f.zoneId())).hasSize(1);
        assertThat(engine.dispatchZone(f.zoneId())).isEmpty();
    }

    @Test
    void eachOrderUsesItsOwnPickupPoint() {
        // The order's pickup is 2 km north; the courier near the zone centre is 2 km away from it.
        UUID nearPickup = f.availableCourier(Fixtures.north(2_100), MINUTE);
        f.availableCourier(Fixtures.north(0), MINUTE);
        f.order(OrderTier.STANDARD, MINUTE, Fixtures.north(2_000));

        assertThat(engine.dispatchZone(f.zoneId())).extracting(Offer::courierId).containsExactly(nearPickup);
    }

    @Test
    void pendingOrdersComeBackInPriorityScoreOrder() {
        UUID s20 = f.order(OrderTier.STANDARD, MINUTE.multipliedBy(20)); // 20
        UUID p5 = f.order(OrderTier.PRIORITY, MINUTE.multipliedBy(5));   // 15
        UUID s12 = f.order(OrderTier.STANDARD, MINUTE.multipliedBy(12)); // 12
        UUID p0 = f.order(OrderTier.PRIORITY, Duration.ZERO);            // 10
        UUID s1 = f.order(OrderTier.STANDARD, MINUTE);                   // 1

        assertThat(repo.pendingOrders(f.zoneId(), 10)).extracting(AssignmentRepository.PendingOrder::id)
                .containsExactly(s20, p5, s12, p0, s1);
        assertThat(repo.pendingOrders(f.zoneId(), 2)).extracting(AssignmentRepository.PendingOrder::id)
                .containsExactly(s20, p5);
    }
}
