package io.github.vivekdavara.dispatch.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.order.CreateOrderRequest;
import io.github.vivekdavara.dispatch.order.OrderService;
import io.github.vivekdavara.dispatch.support.Fixtures;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Real sockets against the running app, with the event-driven loop on: couriers connect, orders come in through
 * the service, and offers, answers and timeouts travel over the wire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dispatch.loop.enabled=true",
        "dispatch.offers.timeout=3s",
        "dispatch.loop.expiry-interval=100ms",
        "dispatch.loop.sweep-interval=1s"})
@DirtiesContext
class CourierSocketTest {

    static final Duration MINUTE = Duration.ofMinutes(1);

    @LocalServerPort
    int port;

    @Autowired
    OrderService orders;

    @Autowired
    CourierSocketHandler hub;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CourierLocations locations;

    Fixtures f;
    final List<Client> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc, redis, locations, "socket");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Client c : clients) {
            c.session.close();
        }
        f.cleanUp();
    }

    /** A connected courier app: every message it receives lands in a queue. */
    final class Client extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
            inbox.add(json.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            closed.complete(status);
        }

        /** Messages read off the queue while looking for another type, kept for later calls. */
        final List<JsonNode> skipped = new ArrayList<>();

        /**
         * The oldest unclaimed message of {@code type}. Messages of other types are kept, not dropped, so a test
         * doesn't depend on the order of messages sent in the same instant (e.g. offer_closed and accepted).
         */
        JsonNode next(String type) throws InterruptedException {
            for (int i = 0; i < skipped.size(); i++) {
                if (skipped.get(i).path("type").asText().equals(type)) {
                    return skipped.remove(i);
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                JsonNode m = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (m == null) {
                    continue;
                }
                if (m.path("type").asText().equals(type)) {
                    return m;
                }
                skipped.add(m);
            }
            throw new AssertionError("no '" + type + "' message within 10 s; other messages: " + skipped);
        }

        void send(String type, long assignmentId) throws Exception {
            session.sendMessage(new TextMessage("{\"type\": \"" + type + "\", \"assignmentId\": " + assignmentId
                    + "}"));
        }
    }

    Client connect(UUID courierId) throws Exception {
        Client c = new Client();
        c.session = new StandardWebSocketClient()
                .execute(c, null, URI.create("ws://localhost:" + port + "/ws/couriers/" + courierId))
                .get(5, TimeUnit.SECONDS);
        clients.add(c);
        return c;
    }

    UUID createOrder() {
        GeoPoint c = Fixtures.CENTER;
        var request = new CreateOrderRequest(f.zoneId(), new CreateOrderRequest.Location(c.lat(), c.lng()),
                new CreateOrderRequest.Location(42.36, -71.06), null);
        return orders.create(UUID.randomUUID().toString(), request).order().id();
    }

    @Test
    void aConnectedCourierIsGreetedAndThenPushedTheOffer() throws Exception {
        UUID courier = f.availableCourier(Fixtures.north(300), MINUTE);
        Client app = connect(courier);
        assertThat(app.next("hello").path("courierId").asText()).isEqualTo(courier.toString());

        UUID order = createOrder();

        JsonNode offer = app.next("offer");
        assertThat(offer.path("orderId").asText()).isEqualTo(order.toString());
        assertThat(offer.path("tier").asText()).isEqualTo("STANDARD");
        assertThat(offer.path("pickup").path("lat").asDouble()).isEqualTo(Fixtures.CENTER.lat());
        assertThat(offer.path("distanceMeters").asDouble()).isBetween(298.0, 302.0);
        Instant offeredAt = Instant.parse(offer.path("offeredAt").asText());
        assertThat(Instant.parse(offer.path("expiresAt").asText())).isEqualTo(offeredAt.plusSeconds(3));
    }

    @Test
    void acceptingOverTheSocketAssignsTheOrder() throws Exception {
        UUID courier = f.availableCourier(Fixtures.north(300), MINUTE);
        Client app = connect(courier);
        UUID order = createOrder();
        long assignment = app.next("offer").path("assignmentId").asLong();

        app.send("accept", assignment);

        assertThat(app.next("accepted").path("assignmentId").asLong()).isEqualTo(assignment);
        assertThat(app.next("offer_closed").path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(f.orderStatus(order)).isEqualTo("ASSIGNED");
        assertThat(f.courierStatus(courier)).isEqualTo("BUSY");
    }

    @Test
    void aDeclineOverTheSocketSendsTheOrderToTheNextCourier() throws Exception {
        UUID near = f.availableCourier(Fixtures.north(200), MINUTE);
        UUID far = f.availableCourier(Fixtures.north(1_500), MINUTE);
        Client nearApp = connect(near);
        Client farApp = connect(far);
        UUID order = createOrder();
        long first = nearApp.next("offer").path("assignmentId").asLong();

        nearApp.send("decline", first);

        nearApp.next("declined");
        JsonNode second = farApp.next("offer");
        assertThat(second.path("orderId").asText()).isEqualTo(order.toString());
        assertThat(second.path("assignmentId").asLong()).isNotEqualTo(first);
    }

    @Test
    void anUnansweredOfferExpiresOnTheWireAndMovesOn() throws Exception {
        UUID near = f.availableCourier(Fixtures.north(200), MINUTE);
        UUID far = f.availableCourier(Fixtures.north(1_500), MINUTE);
        Client nearApp = connect(near);
        Client farApp = connect(far);
        UUID order = createOrder();
        long first = nearApp.next("offer").path("assignmentId").asLong();

        // Nobody answers; the timeout here is 3 s.
        JsonNode closed = nearApp.next("offer_closed");

        assertThat(closed.path("assignmentId").asLong()).isEqualTo(first);
        assertThat(closed.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(farApp.next("offer").path("orderId").asText()).isEqualTo(order.toString());
        // Too late to accept the expired one.
        nearApp.send("accept", first);
        assertThat(nearApp.next("error").path("status").asInt()).isEqualTo(409);
    }

    @Test
    void reconnectingMidOfferResendsTheOpenOffer() throws Exception {
        UUID courier = f.availableCourier(Fixtures.north(300), MINUTE);
        Client first = connect(courier);
        createOrder();
        long assignment = first.next("offer").path("assignmentId").asLong();
        first.session.close();
        awaitDisconnected(courier);

        Client second = connect(courier);

        second.next("hello");
        assertThat(second.next("offer").path("assignmentId").asLong()).isEqualTo(assignment);
    }

    @Test
    void aSecondConnectionReplacesTheFirst() throws Exception {
        UUID courier = f.availableCourier(Fixtures.north(300), MINUTE);
        Client old = connect(courier);
        old.next("hello");

        Client replacement = connect(courier);
        replacement.next("hello");

        assertThat(old.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4001);
        createOrder();
        assertThat(replacement.next("offer").path("type").asText()).isEqualTo("offer");
        assertThat(hub.isConnected(courier)).isTrue();
    }

    @Test
    void anUnknownCourierIsRefused() throws Exception {
        Client stranger = connect(UUID.randomUUID());

        assertThat(stranger.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4004);
    }

    @Test
    void badMessagesGetAnErrorAndTheSocketStaysOpen() throws Exception {
        UUID courier = f.availableCourier(Fixtures.north(300), MINUTE);
        Client app = connect(courier);
        app.next("hello");

        app.session.sendMessage(new TextMessage("not json"));
        assertThat(app.next("error").path("status").asInt()).isEqualTo(400);
        app.send("teleport", 1);
        assertThat(app.next("error").path("detail").asText()).contains("unknown message type 'teleport'");
        app.send("accept", Long.MAX_VALUE);
        assertThat(app.next("error").path("status").asInt()).isEqualTo(404);

        assertThat(app.session.isOpen()).isTrue();
    }

    void awaitDisconnected(UUID courier) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hub.isConnected(courier)) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
    }
}
