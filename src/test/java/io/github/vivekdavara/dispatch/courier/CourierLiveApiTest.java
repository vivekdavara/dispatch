package io.github.vivekdavara.dispatch.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.vivekdavara.dispatch.domain.GeoPoint;
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
import org.springframework.transaction.annotation.Transactional;

/** Location pings and online/offline, against real Postgres (rolled back) and Redis (cleaned up). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CourierLiveApiTest {

    static final GeoPoint CENTER = new GeoPoint(42.35, -71.08);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CourierLocations locations;

    @Autowired
    CourierRepository repo;

    @Autowired
    StringRedisTemplate redis;

    final String zone = "live-" + UUID.randomUUID().toString().substring(0, 8);

    UUID courier;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("INSERT INTO zones (id, name, center_lat, center_lng, radius_km) VALUES (?, ?, ?, ?, ?)",
                zone, zone, CENTER.lat(), CENTER.lng(), 3.0);
        String body = mvc.perform(post("/api/v1/couriers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\": \"" + zone + "\", \"name\": \"Ana\"}"))
                .andReturn().getResponse().getContentAsString();
        courier = UUID.fromString(JsonPath.read(body, "$.id"));
    }

    @AfterEach
    void cleanUp() {
        redis.delete(CourierLocations.geoKey(zone));
        redis.delete(CourierLocations.seenKey(courier));
    }

    ResultActions ping(UUID id, String json) throws Exception {
        return mvc.perform(put("/api/v1/couriers/" + id + "/location")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions setStatus(String status) throws Exception {
        return mvc.perform(put("/api/v1/couriers/" + courier + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"" + status + "\"}"));
    }

    @Test
    void pingStoresThePositionInRedis() throws Exception {
        ping(courier, "{\"lat\": 42.351, \"lng\": -71.08}").andExpect(status().isNoContent());
        assertThat(locations.freshWithin(zone, CENTER, 500)).extracting(CourierLocations.Nearby::courierId)
                .containsExactly(courier);
    }

    @Test
    void pingForUnknownCourierIs404() throws Exception {
        ping(UUID.randomUUID(), "{\"lat\": 42.351, \"lng\": -71.08}").andExpect(status().isNotFound());
    }

    @Test
    void pingWithBadCoordinatesIs400() throws Exception {
        ping(courier, "{\"lat\": 42.351, \"lng\": -181}").andExpect(status().isBadRequest());
        ping(courier, "{\"lat\": 42.351}").andExpect(status().isBadRequest());
    }

    @Test
    void goingOnlineMakesTheCourierAvailableAndStampsIdleSince() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        setStatus("AVAILABLE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));
        assertThat(repo.find(courier).orElseThrow().idleSince()).isAfter(before);
    }

    @Test
    void repeatingTheCurrentStatusIsANoOp() throws Exception {
        setStatus("AVAILABLE");
        Instant idle = repo.find(courier).orElseThrow().idleSince();
        setStatus("AVAILABLE").andExpect(status().isOk());
        assertThat(repo.find(courier).orElseThrow().idleSince()).isEqualTo(idle);
    }

    @Test
    void goingOfflineDropsThePosition() throws Exception {
        setStatus("AVAILABLE");
        ping(courier, "{\"lat\": 42.351, \"lng\": -71.08}");
        setStatus("OFFLINE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFFLINE"));
        assertThat(locations.freshWithin(zone, CENTER, 500)).isEmpty();
    }

    @Test
    void anOfferedCourierCantGoOffline() throws Exception {
        setStatus("AVAILABLE");
        repo.transition(courier, CourierStatus.AVAILABLE, CourierStatus.OFFERED, Instant.now());
        setStatus("OFFLINE").andExpect(status().isConflict());
    }

    @Test
    void engineOnlyStatusesAre422() throws Exception {
        setStatus("BUSY").andExpect(status().isUnprocessableEntity());
        setStatus("OFFERED").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownStatusIs400() throws Exception {
        setStatus("ON_BREAK").andExpect(status().isBadRequest());
    }
}
