package io.github.vivekdavara.dispatch.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vivekdavara.dispatch.courier.CourierLocations;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * One delivery through the public surface only, the way the two apps would drive it: HTTP for setup, orders,
 * pickup and delivery; the courier's WebSocket for the offer and the accept. Nothing calls a service directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dispatch.loop.enabled=true")
@DirtiesContext
class DeliveryFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    final String zone = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
    UUID courier;
    WebSocketSession socket;

    @AfterEach
    void tearDown() throws Exception {
        if (socket != null) {
            socket.close();
        }
        redis.delete(CourierLocations.geoKey(zone));
        if (courier != null) {
            redis.delete(CourierLocations.seenKey(courier));
        }
        jdbc.update("DELETE FROM assignments WHERE courier_id IN (SELECT id FROM couriers WHERE zone_id = ?)", zone);
        jdbc.update("DELETE FROM orders WHERE zone_id = ?", zone);
        jdbc.update("DELETE FROM couriers WHERE zone_id = ?", zone);
        jdbc.update("DELETE FROM zones WHERE id = ?", zone);
    }

    JsonNode call(HttpMethod method, String path, Object body, HttpStatus expected) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        if (path.equals("/api/v1/orders")) {
            headers.set("Idempotency-Key", "e2e-" + UUID.randomUUID());
        }
        ResponseEntity<String> r = http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        assertThat(r.getStatusCode()).as("%s %s: %s", method, path, r.getBody()).isEqualTo(expected);
        return r.getBody() == null ? null : json.readTree(r.getBody());
    }

    @Test
    void anOrderGoesFromCheckoutToDoor() throws Exception {
        call(HttpMethod.PUT, "/api/v1/zones/" + zone,
                Map.of("name", "E2E", "centerLat", 42.35, "centerLng", -71.08, "radiusKm", 3.0), HttpStatus.OK);
        courier = UUID.fromString(call(HttpMethod.POST, "/api/v1/couriers",
                Map.of("zoneId", zone, "name", "Ana"), HttpStatus.CREATED).path("id").asText());
        call(HttpMethod.PUT, "/api/v1/couriers/" + courier + "/location", Map.of("lat", 42.352, "lng", -71.08),
                HttpStatus.NO_CONTENT);
        call(HttpMethod.PUT, "/api/v1/couriers/" + courier + "/status", Map.of("status", "AVAILABLE"),
                HttpStatus.OK);

        BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        socket = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                inbox.add(json.readTree(m.getPayload()));
            }
        }, null, URI.create("ws://localhost:" + port + "/ws/couriers/" + courier)).get(5, TimeUnit.SECONDS);
        assertThat(take(inbox).path("type").asText()).isEqualTo("hello");

        long created = System.nanoTime();
        UUID order = UUID.fromString(call(HttpMethod.POST, "/api/v1/orders", Map.of(
                "zoneId", zone,
                "pickup", Map.of("lat", 42.35, "lng", -71.08),
                "dropoff", Map.of("lat", 42.36, "lng", -71.06),
                "tier", "PRIORITY"), HttpStatus.CREATED).path("id").asText());

        JsonNode offer = take(inbox);
        Duration toOffer = Duration.ofNanos(System.nanoTime() - created);
        assertThat(offer.path("type").asText()).isEqualTo("offer");
        assertThat(offer.path("orderId").asText()).isEqualTo(order.toString());
        assertThat(offer.path("tier").asText()).isEqualTo("PRIORITY");
        // Event-driven: well inside the 5 s sweep interval, so it wasn't the sweep that found it.
        assertThat(toOffer).isLessThan(Duration.ofSeconds(2));
        long assignment = offer.path("assignmentId").asLong();

        socket.sendMessage(new TextMessage("{\"type\": \"accept\", \"assignmentId\": " + assignment + "}"));
        assertThat(List.of(take(inbox).path("type").asText(), take(inbox).path("type").asText()))
                .containsExactlyInAnyOrder("accepted", "offer_closed");
        assertThat(call(HttpMethod.GET, "/api/v1/orders/" + order, null, HttpStatus.OK).path("status").asText())
                .isEqualTo("ASSIGNED");

        Map<String, String> me = Map.of("courierId", courier.toString());
        call(HttpMethod.POST, "/api/v1/assignments/" + assignment + "/pickup", me, HttpStatus.OK);
        call(HttpMethod.POST, "/api/v1/assignments/" + assignment + "/deliver", me, HttpStatus.OK);

        assertThat(call(HttpMethod.GET, "/api/v1/orders/" + order, null, HttpStatus.OK).path("status").asText())
                .isEqualTo("DELIVERED");
        assertThat(call(HttpMethod.GET, "/api/v1/couriers/" + courier, null, HttpStatus.OK).path("status").asText())
                .isEqualTo("AVAILABLE");
        assertThat(call(HttpMethod.GET, "/api/v1/assignments/" + assignment, null, HttpStatus.OK)
                .path("status").asText()).isEqualTo("COMPLETED");
    }

    static JsonNode take(BlockingQueue<JsonNode> inbox) throws InterruptedException {
        JsonNode m = inbox.poll(10, TimeUnit.SECONDS);
        assertThat(m).as("a socket message within 10 s").isNotNull();
        return m;
    }
}
