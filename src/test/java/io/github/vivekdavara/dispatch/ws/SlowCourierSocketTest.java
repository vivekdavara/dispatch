package io.github.vivekdavara.dispatch.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.vivekdavara.dispatch.assignment.AssignmentEngine;
import io.github.vivekdavara.dispatch.assignment.Offer;
import io.github.vivekdavara.dispatch.assignment.OfferService;
import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.support.Fixtures;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;

/**
 * A courier socket that can't take any more (a send stuck past the time limit, or a full buffer): Spring's session
 * decorator throws {@link SessionLimitExceededException} on the thread that tried to send. Pushes happen on engine
 * threads and right after answers commit, so that exception must stay at the socket: the pass still makes its
 * offers, the answer still succeeds, and the session is closed so the courier's grace period starts.
 */
@SpringBootTest
class SlowCourierSocketTest {

    @Autowired
    CourierSocketHandler hub;

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
    WebSocketSession raw;

    @BeforeEach
    void setUp() throws Exception {
        f = new Fixtures(jdbc, redis, locations, "slow-socket");
        courier = f.availableCourier(Fixtures.north(300), Duration.ofMinutes(5));
        raw = mock(WebSocketSession.class);
        when(raw.getUri()).thenReturn(URI.create("ws://localhost/ws/couriers/" + courier));
        when(raw.getAttributes()).thenReturn(new HashMap<>());
        when(raw.getId()).thenReturn("slow-" + courier);
        when(raw.isOpen()).thenReturn(true);
        hub.afterConnectionEstablished(raw); // hello goes through
        doThrow(new SessionLimitExceededException("send time exceeded", CloseStatus.SESSION_NOT_RELIABLE))
                .when(raw).sendMessage(any());
    }

    @AfterEach
    void tearDown() throws Exception {
        doNothing().when(raw).sendMessage(any());
        hub.afterConnectionClosed(raw, CloseStatus.NORMAL);
        f.cleanUp();
    }

    @Test
    void anOfferToASocketOverItsLimitsStillLeavesThePassItsOffers() throws Exception {
        UUID order = f.order(OrderTier.STANDARD, Duration.ofMinutes(1));

        List<Offer> made = engine.dispatchZone(f.zoneId());

        assertThat(made).extracting(Offer::orderId).containsExactly(order);
        assertThat(f.courierStatus(courier)).isEqualTo("OFFERED");
        verify(raw, timeout(2_000)).close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Test
    void anAnswerStillSucceedsWhenItsAcknowledgementCantBeSent() throws Exception {
        doNothing().when(raw).sendMessage(any());
        f.order(OrderTier.STANDARD, Duration.ofMinutes(1));
        Offer offer = engine.dispatchZone(f.zoneId()).get(0);
        doThrow(new SessionLimitExceededException("buffer full", CloseStatus.SESSION_NOT_RELIABLE))
                .when(raw).sendMessage(any());

        // The decline commits, then its offer_closed push hits the full socket.
        offers.decline(offer.assignmentId(), courier);

        assertThat(f.assignmentStatus(offer.assignmentId())).isEqualTo("DECLINED");
        assertThat(f.courierStatus(courier)).isEqualTo("AVAILABLE");
    }
}
