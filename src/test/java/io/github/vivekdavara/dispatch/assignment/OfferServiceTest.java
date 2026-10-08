package io.github.vivekdavara.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.support.Fixtures;
import io.github.vivekdavara.dispatch.web.ApiErrors;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Accepting and declining offers, against real Postgres and Redis. */
@SpringBootTest
class OfferServiceTest {

    static final Duration MINUTE = Duration.ofMinutes(1);

    @Autowired
    AssignmentEngine engine;

    @Autowired
    OfferService offers;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CourierLocations locations;

    Fixtures f;
    UUID courier;
    UUID order;
    Offer offer;

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc, redis, locations, "offer");
        courier = f.availableCourier(Fixtures.north(300), MINUTE.multipliedBy(30));
        order = f.order(OrderTier.STANDARD, MINUTE);
        offer = engine.dispatchZone(f.zoneId()).get(0);
    }

    @AfterEach
    void tearDown() {
        f.cleanUp();
    }

    @Test
    void acceptAssignsTheOrderAndMakesTheCourierBusy() {
        Assignment a = offers.accept(offer.assignmentId(), courier);

        assertThat(a.status()).isEqualTo(AssignmentStatus.ACCEPTED);
        assertThat(a.respondedAt()).isNotNull();
        assertThat(f.assignmentStatus(offer.assignmentId())).isEqualTo("ACCEPTED");
        assertThat(f.orderStatus(order)).isEqualTo("ASSIGNED");
        assertThat(f.courierStatus(courier)).isEqualTo("BUSY");
    }

    @Test
    void declineReturnsBothToThePoolAndResetsIdleTime() {
        Instant before = Instant.now().minusSeconds(1);

        Assignment a = offers.decline(offer.assignmentId(), courier);

        assertThat(a.status()).isEqualTo(AssignmentStatus.DECLINED);
        assertThat(f.orderStatus(order)).isEqualTo("PENDING");
        assertThat(f.courierStatus(courier)).isEqualTo("AVAILABLE");
        // Idle for 30 minutes before; after declining, the courier is at the back of the fairness queue.
        assertThat(f.idleSince(courier)).isAfter(before);
    }

    @Test
    void anOfferCanBeAnsweredOnlyOnce() {
        offers.accept(offer.assignmentId(), courier);

        assertThatThrownBy(() -> offers.accept(offer.assignmentId(), courier))
                .isInstanceOf(ApiErrors.ConflictException.class).hasMessageContaining("ACCEPTED");
        assertThatThrownBy(() -> offers.decline(offer.assignmentId(), courier))
                .isInstanceOf(ApiErrors.ConflictException.class);
        assertThat(f.orderStatus(order)).isEqualTo("ASSIGNED");
    }

    @Test
    void anotherCouriersOfferLooksLikeItDoesNotExist() {
        UUID stranger = f.courier("AVAILABLE", MINUTE);

        assertThatThrownBy(() -> offers.accept(offer.assignmentId(), stranger))
                .isInstanceOf(ApiErrors.NotFoundException.class);
        assertThatThrownBy(() -> offers.decline(Long.MAX_VALUE, courier))
                .isInstanceOf(ApiErrors.NotFoundException.class);
        assertThat(f.assignmentStatus(offer.assignmentId())).isEqualTo("OFFERED");
    }

    @Test
    void aCourierWhoDeclinedIsNotOfferedThatOrderAgain() {
        UUID other = f.availableCourier(Fixtures.north(1_500), MINUTE);
        offers.decline(offer.assignmentId(), courier);

        // The decliner is still nearest, but the order goes to the next courier.
        assertThat(engine.dispatchZone(f.zoneId())).extracting(Offer::orderId, Offer::courierId)
                .containsExactly(tuple(order, other));
    }

    @Test
    void aCourierWhoDeclinedOneOrderStillGetsOthers() {
        offers.decline(offer.assignmentId(), courier);
        UUID second = f.order(OrderTier.STANDARD, Duration.ZERO);

        assertThat(engine.dispatchZone(f.zoneId())).extracting(Offer::orderId, Offer::courierId)
                .containsExactly(tuple(second, courier));
        assertThat(f.orderStatus(order)).isEqualTo("PENDING");
    }

    @RepeatedTest(5)
    void acceptRacingDeclineHasExactlyOneWinner() {
        CountDownLatch start = new CountDownLatch(1);
        var accept = CompletableFuture.supplyAsync(() -> attempt(start, () -> offers.accept(offer.assignmentId(),
                courier)));
        var decline = CompletableFuture.supplyAsync(() -> attempt(start, () -> offers.decline(offer.assignmentId(),
                courier)));
        start.countDown();

        List<Boolean> won = List.of(accept.join(), decline.join());
        assertThat(won).containsExactlyInAnyOrder(true, false);
        if (won.get(0)) {
            assertThat(List.of(f.orderStatus(order), f.courierStatus(courier))).containsExactly("ASSIGNED", "BUSY");
        } else {
            assertThat(List.of(f.orderStatus(order), f.courierStatus(courier)))
                    .containsExactly("PENDING", "AVAILABLE");
        }
    }

    static boolean attempt(CountDownLatch start, Runnable answer) {
        try {
            start.await();
            answer.run();
            return true;
        } catch (ApiErrors.ConflictException lost) {
            return false;
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
