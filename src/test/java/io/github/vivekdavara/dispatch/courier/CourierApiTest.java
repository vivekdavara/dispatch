package io.github.vivekdavara.dispatch.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CourierApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CourierRepository repo;

    @BeforeEach
    void zone() {
        jdbc.update("INSERT INTO zones (id, name, center_lat, center_lng, radius_km) VALUES (?, ?, ?, ?, ?)",
                "courier-zone", "Courier test zone", 42.35, -71.08, 3.0);
    }

    String register(String zone, String name) throws Exception {
        return mvc.perform(post("/api/v1/couriers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\": \"" + zone + "\", \"name\": \"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void registeredCourierStartsOffline() throws Exception {
        mvc.perform(post("/api/v1/couriers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\": \"courier-zone\", \"name\": \"Ana\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.name").value("Ana"))
                .andExpect(jsonPath("$.idleSince").doesNotExist());
    }

    @Test
    void getReturnsTheCourier() throws Exception {
        String id = JsonPath.read(register("courier-zone", "Ana"), "$.id");
        mvc.perform(get("/api/v1/couriers/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void unknownZoneIs422() throws Exception {
        mvc.perform(post("/api/v1/couriers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\": \"mars\", \"name\": \"Ana\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void blankNameIs400() throws Exception {
        mvc.perform(post("/api/v1/couriers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\": \"courier-zone\", \"name\": \" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCourierIs404() throws Exception {
        mvc.perform(get("/api/v1/couriers/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void transitionIsCompareAndSet() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(register("courier-zone", "Ana"), "$.id"));
        Instant t = Instant.parse("2026-10-07T12:00:00Z");
        assertThat(repo.transition(id, CourierStatus.OFFLINE, CourierStatus.AVAILABLE, t)).isTrue();
        // Already AVAILABLE, so a second OFFLINE -> AVAILABLE loses.
        assertThat(repo.transition(id, CourierStatus.OFFLINE, CourierStatus.AVAILABLE, t.plusSeconds(5))).isFalse();
        assertThat(repo.find(id).orElseThrow().idleSince()).isEqualTo(t);
    }

    @Test
    void leavingAvailableKeepsIdleSince() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(register("courier-zone", "Ana"), "$.id"));
        Instant t = Instant.parse("2026-10-07T12:00:00Z");
        repo.transition(id, CourierStatus.OFFLINE, CourierStatus.AVAILABLE, t);
        assertThat(repo.transition(id, CourierStatus.AVAILABLE, CourierStatus.OFFERED, t.plusSeconds(60))).isTrue();
        Courier c = repo.find(id).orElseThrow();
        assertThat(c.status()).isEqualTo(CourierStatus.OFFERED);
        assertThat(c.idleSince()).isEqualTo(t);
    }
}
