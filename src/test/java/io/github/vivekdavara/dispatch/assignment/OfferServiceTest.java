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
    void anAcceptedOfferCannotBeDeclined() {
        offers.accept(offer.assignmentId(), courier);

        assertThatThrownBy(() -> offers.decline(offer.assignmentId(), courier))
                .isInstanceOf(ApiErrors.ConflictException.class).hasMessageContaining("ACCEPTED");
        assertThat(f.orderStatus(order)).isEqualTo("ASSIGNED");
    }

    @Test
    void aDeclinedOfferCannotBeAccepted() {
        offers.decline(offer.assignmentId(), courier);

        assertThatThrownBy(() -> offers.accept(offer.assignmentId(), courier))
                .isInstanceOf(ApiErrors.ConflictException.class).hasMessageContaining("DECLINED");
        assertThat(f.orderStatus(order)).isEqualTo("PENDING");
    }

    @Test
    void repeatingAnAcceptReturnsTheSameAssignmentAndChangesNothing() {
        Assignment first = offers.accept(offer.assignmentId(), courier);

        Assignment again = offers.accept(offer.assignmentId(), courier);

        assertThat(again).isEqualTo(first);
        assertThat(List.of(f.orderStatus(order), f.courierStatus(courier))).containsExactly("ASSIGNED", "BUSY");
    }

    @Test
    void repeatingAnAcceptAfterDeliveryIsStillHarmless() {
        offers.accept(offer.assignmentId(), courier);
        offers.pickedUp(offer.assignmentId(), courier);
        offers.delivered(offer.assignmentId(), courier);

        assertThat(offers.accept(offer.assignmentId(), courier).status()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(f.courierStatus(courier)).isEqualTo("AVAILABLE");
    }

    @Test
    void repeatingADeclineReturnsTheSameAssignmentAndChangesNothing() {
        Assignment first = offers.decline(offer.assignmentId(), courier);
        Instant idle = f.idleSince(courier);

        Assignment again = offers.decline(offer.assignmentId(), courier);

        assertThat(again).isEqualTo(first);
        assertThat(f.idleSince(courier)).isEqualTo(idle);
        assertThat(f.orderStatus(order)).isEqualTo("PENDING");
    }

    @Test
    void anotherCourierRepeatingSomeoneElsesAnswerStillGetsNotFound() {
        offers.accept(offer.assignmentId(), courier);

        assertThatThrownBy(() -> offers.accept(offer.assignmentId(), UUID.randomUUID()))
                .isInstanceOf(ApiErrors.NotFoundException.class);
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

    @Test
    void anUnansweredOfferExpiresAfterTheTimeout() {
        backdate(offer.assignmentId(), Duration.ofSeconds(31));

        List<Assignment> expired = offers.expireDue();

        assertThat(expired).extracting(Assignment::id).contains(offer.assignmentId());
        assertThat(f.assignmentStatus(offer.assignmentId())).isEqualTo("EXPIRED");
        assertThat(f.orderStatus(order)).isEqualTo("PENDING");
        assertThat(f.courierStatus(courier)).isEqualTo("AVAILABLE");
        assertThatThrownBy(() -> offers.accept(offer.assignmentId(), courier))
                .isInstanceOf(ApiErrors.ConflictException.class).hasMessageContaining("EXPIRED");
    }

    @Test
    void aYoungOfferIsLeftAlone() {
        backdate(offer.assignmentId(), Duration.ofSeconds(25));

        assertThat(offers.expireDue()).extracting(Assignment::id).doesNotContain(offer.assignmentId());
        assertThat(f.assignmentStatus(offer.assignmentId())).isEqualTo("OFFERED");
    }

    @Test
    void anExpiredOrderGoesToTheNextCourier() {
        UUID other = f.availableCourier(Fixtures.north(2_000), MINUTE);
        backdate(offer.assignmentId(), Duration.ofMinutes(5));
        offers.expireDue();

        assertThat(engine.dispatchZone(f.zoneId())).extracting(Offer::orderId, Offer::courierId)
                .containsExactly(tuple(order, other));
    }

    @Test
    void expiresAtIsTheOfferTimePlusTheTimeout() {
        assertThat(offers.expiresAt(offer.offeredAt())).isEqualTo(offer.offeredAt().plusSeconds(30));
    }

    @RepeatedTest(5)
    void acceptRacingExpiryHasExactlyOneWinner() {
        backdate(offer.assignmentId(), Duration.ofSeconds(31));
        CountDownLatch start = new CountDownLatch(1);
        var accept = CompletableFuture.supplyAsync(() -> attempt(start, () -> offers.accept(offer.assignmentId(),
                courier)));
        var expire = CompletableFuture.supplyAsync(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return offers.expireDue().stream().anyMatch(a -> a.id() == offer.assignmentId());
        });
        start.countDown();

        assertThat(List.of(accept.join(), expire.join())).containsExactlyInAnyOrder(true, false);
        String expected = accept.join() ? "ACCEPTED" : "EXPIRED";
        assertThat(f.assignmentStatus(offer.assignmentId())).isEqualTo(expected);
    }

    @Test
    void aDisconnectedCourierLosesTheirOfferAndGoesOfflineInOneStep() {
        OfferService.Disconnect d = offers.disconnected(courier);

        assertThat(d.wentOffline()).isTrue();
        assertThat(d.released()).map(Assignment::id).contains(offer.assignmentId());
        assertThat(f.assignmentStatus(offer.assignmentId())).isEqualTo("EXPIRED");
        assertThat(f.orderStatus(order)).isEqualTo("PENDING");
        assertThat(f.courierStatus(courier)).isEqualTo("OFFLINE");
        assertThat(redis.hasKey(CourierLocations.seenKey(courier))).isFalse();
    }

    @Test
    void aDisconnectedIdleCourierJustGoesOffline() {
        UUID idle = f.availableCourier(Fixtures.north(2_000), Duration.ofMinutes(5));

        OfferService.Disconnect d = offers.disconnected(idle);

        assertThat(d.wentOffline()).isTrue();
        assertThat(d.released()).isEmpty();
        assertThat(f.courierStatus(idle)).isEqualTo("OFFLINE");
    }

    @Test
    void aDisconnectedBusyCourierIsLeftAlone() {
        offers.accept(offer.assignmentId(), courier);

        OfferService.Disconnect d = offers.disconnected(courier);

        assertThat(d.wentOffline()).isFalse();
        assertThat(List.of(f.courierStatus(courier), f.assignmentStatus(offer.assignmentId())))
                .containsExactly("BUSY", "ACCEPTED");
    }

    @Test
    void aDisconnectForAnUnknownCourierDoesNothing() {
        assertThat(offers.disconnected(UUID.randomUUID()).wentOffline()).isFalse();
    }

    /** Pretends the offer was made {@code age} ago. */
    void backdate(long assignmentId, Duration age) {
        jdbc.update("UPDATE assignments SET offered_at = now() - ? * interval '1 second' WHERE id = ?",
                age.toSeconds(), assignmentId);
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
