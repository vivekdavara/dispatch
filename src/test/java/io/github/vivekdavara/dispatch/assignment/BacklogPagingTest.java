package io.github.vivekdavara.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.support.Fixtures;
import io.micrometer.core.instrument.MeterRegistry;
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

/**
 * A pass reads pending orders a batch at a time (three here, 200 in production). Orders at the front of the queue
 * that no free courier can take (every one of them refused it, or is too far away) must not hide the orders behind
 * them: while free couriers are left, the pass reads the next batch.
 */
@SpringBootTest(properties = "dispatch.assignment.pending-batch=3")
class BacklogPagingTest {

    static final Duration MINUTE = Duration.ofMinutes(1);

    @Autowired
    AssignmentEngine engine;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CourierLocations locations;

    @Autowired
    MeterRegistry meters;

    Fixtures f;

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc, redis, locations, "paging");
    }

    @AfterEach
    void tearDown() {
        f.cleanUp();
    }

    /** {@code courier} declined {@code order} earlier. */
    void refused(UUID order, UUID courier) {
        jdbc.update("""
                INSERT INTO assignments (order_id, courier_id, status, distance_m, offered_at, responded_at)
                VALUES (?, ?, 'DECLINED', 100, now() - interval '2 minutes', now() - interval '1 minute')""",
                order, courier);
    }

    double examined() {
        return meters.get("dispatch.pass.orders").summary().totalAmount();
    }

    @Test
    void ordersEveryFreeCourierRefusedDoNotHideTheOrdersBehindThem() {
        UUID courier = f.availableCourier(Fixtures.north(200), MINUTE);
        for (int i = 0; i < 3; i++) {
            refused(f.order(OrderTier.STANDARD, MINUTE.multipliedBy(10 + i)), courier);
        }
        UUID servable = f.order(OrderTier.STANDARD, MINUTE);

        List<Offer> offers = engine.dispatchZone(f.zoneId());

        assertThat(offers).extracting(Offer::orderId).containsExactly(servable);
        assertThat(offers).extracting(Offer::courierId).containsExactly(courier);
    }

    @Test
    void ordersOutOfEveryFreeCouriersRangeDoNotHideTheOrdersBehindThem() {
        // Both ends of the 3 km zone: 5.8 km apart, past the 5 km pickup limit.
        UUID courier = f.availableCourier(Fixtures.north(2_900), MINUTE);
        for (int i = 0; i < 4; i++) {
            f.order(OrderTier.STANDARD, MINUTE.multipliedBy(10 + i), Fixtures.north(-2_900));
        }
        UUID servable = f.order(OrderTier.STANDARD, MINUTE, Fixtures.north(2_800));

        List<Offer> offers = engine.dispatchZone(f.zoneId());

        assertThat(offers).extracting(Offer::orderId).containsExactly(servable);
        assertThat(offers).extracting(Offer::courierId).containsExactly(courier);
    }

    @Test
    void aPassThatCanServeNothingReadsTheWholeBacklogOnceAndStops() {
        UUID courier = f.availableCourier(Fixtures.north(200), MINUTE);
        for (int i = 0; i < 7; i++) {
            refused(f.order(OrderTier.STANDARD, MINUTE.multipliedBy(i + 1)), courier);
        }
        double before = examined();

        assertThat(engine.dispatchZone(f.zoneId())).isEmpty();

        // Pages of 3, 3 and 1: every order looked at once, and the short last page ends the pass.
        assertThat(examined()).isEqualTo(before + 7);
    }

    @Test
    void noNextBatchIsReadOnceTheFreeCouriersAreTaken() {
        f.availableCourier(Fixtures.north(200), MINUTE.multipliedBy(3));
        f.availableCourier(Fixtures.north(400), MINUTE.multipliedBy(2));
        for (int i = 0; i < 6; i++) {
            f.order(OrderTier.STANDARD, MINUTE.multipliedBy(i + 1));
        }
        double before = examined();

        assertThat(engine.dispatchZone(f.zoneId())).hasSize(2);

        assertThat(examined()).isEqualTo(before + 2);
    }

    @Test
    void aPriorityOrderBehindAFullBatchOfRefusedOnesIsStillServedFirst() {
        // The second batch is read in the same serving order as the first: a PRIORITY order 1 minute old counts
        // as 11 minutes, so it comes before the 5-minute STANDARD order on the same page.
        UUID courier = f.availableCourier(Fixtures.north(200), MINUTE);
        for (int i = 0; i < 3; i++) {
            refused(f.order(OrderTier.STANDARD, MINUTE.multipliedBy(20 + i)), courier);
        }
        f.order(OrderTier.STANDARD, MINUTE.multipliedBy(5));
        UUID priority = f.order(OrderTier.PRIORITY, MINUTE);

        List<Offer> offers = engine.dispatchZone(f.zoneId());

        assertThat(offers).extracting(Offer::orderId).containsExactly(priority);
    }
}
