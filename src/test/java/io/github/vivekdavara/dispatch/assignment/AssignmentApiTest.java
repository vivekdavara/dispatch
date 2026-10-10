package io.github.vivekdavara.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.support.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The courier's HTTP side of an assignment: answer the offer, pick up, deliver. */
@SpringBootTest
@AutoConfigureMockMvc
class AssignmentApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AssignmentEngine engine;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CourierLocations locations;

    Fixtures f;
    UUID courier;
    UUID order;
    long assignment;

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc, redis, locations, "assign-api");
        courier = f.availableCourier(Fixtures.north(400), Duration.ofMinutes(3));
        order = f.order(OrderTier.STANDARD, Duration.ofMinutes(1));
        assignment = engine.dispatchZone(f.zoneId()).get(0).assignmentId();
    }

    @AfterEach
    void tearDown() {
        f.cleanUp();
    }

    ResultActions act(String action, UUID as) throws Exception {
        return mvc.perform(post("/api/v1/assignments/" + assignment + "/" + action)
                .contentType(MediaType.APPLICATION_JSON).content("{\"courierId\": \"" + as + "\"}"));
    }

    @Test
    void aDeliveryFromOfferToDoorFreesTheCourier() throws Exception {
        act("accept", courier).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(f.orderStatus(order)).isEqualTo("ASSIGNED");

        act("pickup", courier).andExpect(status().isOk());
        assertThat(f.orderStatus(order)).isEqualTo("PICKED_UP");

        act("deliver", courier).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(f.orderStatus(order)).isEqualTo("DELIVERED");
        assertThat(f.courierStatus(courier)).isEqualTo("AVAILABLE");
        assertThat(f.assignmentStatus(assignment)).isEqualTo("COMPLETED");
    }

    @Test
    void aFreedCourierCanTakeTheNextOrder() throws Exception {
        act("accept", courier);
        act("pickup", courier);
        act("deliver", courier);
        UUID next = f.order(OrderTier.STANDARD, Duration.ZERO);

        assertThat(engine.dispatchZone(f.zoneId())).extracting(Offer::orderId).containsExactly(next);
    }

    @Test
    void deliveringBeforePickupIsAConflictAndChangesNothing() throws Exception {
        act("accept", courier);

        act("deliver", courier).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("order " + order + " has to be picked up first"));
        assertThat(f.assignmentStatus(assignment)).isEqualTo("ACCEPTED");
        assertThat(f.courierStatus(courier)).isEqualTo("BUSY");
    }

    @Test
    void pickingUpBeforeAcceptingIsAConflict() throws Exception {
        act("pickup", courier).andExpect(status().isConflict());
        assertThat(f.orderStatus(order)).isEqualTo("OFFERED");
    }

    @Test
    void pickingUpTwiceIsAConflict() throws Exception {
        act("accept", courier);
        act("pickup", courier).andExpect(status().isOk());
        act("pickup", courier).andExpect(status().isConflict());
    }

    @Test
    void declineOverHttp() throws Exception {
        act("decline", courier).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        assertThat(f.orderStatus(order)).isEqualTo("PENDING");
    }

    @Test
    void anotherCourierGetsA404() throws Exception {
        act("accept", UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void theCourierIdIsRequired() throws Exception {
        mvc.perform(post("/api/v1/assignments/" + assignment + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void aCourierWithoutASocketFindsTheirOpenOfferOverHttp() throws Exception {
        mvc.perform(get("/api/v1/couriers/" + courier + "/offer")).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("offer"))
                .andExpect(jsonPath("$.assignmentId").value(assignment))
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.tier").value("STANDARD"))
                .andExpect(jsonPath("$.pickup.lat").value(Fixtures.CENTER.lat()))
                .andExpect(jsonPath("$.expiresAt").exists());
        // The answer window is the same one the socket would have shown.
        String json = mvc.perform(get("/api/v1/couriers/" + courier + "/offer")).andReturn().getResponse()
                .getContentAsString();
        Instant offeredAt = Instant.parse(JsonPath.read(json, "$.offeredAt"));
        Instant expiresAt = Instant.parse(JsonPath.read(json, "$.expiresAt"));
        assertThat(Duration.between(offeredAt, expiresAt)).isEqualTo(Duration.ofSeconds(30));

        act("decline", courier).andExpect(status().isOk());
        mvc.perform(get("/api/v1/couriers/" + courier + "/offer")).andExpect(status().isNoContent());
    }

    @Test
    void anUnknownCourierHasNoOffer() throws Exception {
        mvc.perform(get("/api/v1/couriers/" + UUID.randomUUID() + "/offer")).andExpect(status().isNotFound());
    }

    @Test
    void getShowsTheAssignment() throws Exception {
        mvc.perform(get("/api/v1/assignments/" + assignment)).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.courierId").value(courier.toString()))
                .andExpect(jsonPath("$.status").value("OFFERED"));
        mvc.perform(get("/api/v1/assignments/" + Long.MAX_VALUE)).andExpect(status().isNotFound());
    }
}
