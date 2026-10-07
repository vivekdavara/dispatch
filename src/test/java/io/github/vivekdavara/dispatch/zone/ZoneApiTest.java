package io.github.vivekdavara.dispatch.zone;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Zones, and the whole day-2 flow over HTTP: create a zone, register a courier, put it online, ping, create an
 * order, dispatch. Commits for real (dispatch runs its own transaction), so it cleans up after itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ZoneApiTest {

    static final String ZONE = "zone-api-test";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        redis.delete("couriers:geo:" + ZONE);
        jdbc.queryForList("SELECT id FROM couriers WHERE zone_id = ?", String.class, ZONE)
                .forEach(id -> redis.delete("courier:seen:" + id));
        jdbc.update("DELETE FROM assignments WHERE order_id IN (SELECT id FROM orders WHERE zone_id = ?)", ZONE);
        jdbc.update("DELETE FROM orders WHERE zone_id = ?", ZONE);
        jdbc.update("DELETE FROM couriers WHERE zone_id = ?", ZONE);
        jdbc.update("DELETE FROM zones WHERE id = ?", ZONE);
    }

    ResultActions json(MockHttpServletRequestBuilder req, String body)
            throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions putZone(String id, double radiusKm) throws Exception {
        return json(put("/api/v1/zones/" + id), """
                {"name": "Back Bay", "centerLat": 42.35, "centerLng": -71.08, "radiusKm": %s}""".formatted(radiusKm));
    }

    @Test
    void putCreatesAndThenUpdatesAZone() throws Exception {
        putZone(ZONE, 3).andExpect(status().isOk()).andExpect(jsonPath("$.radiusKm").value(3.0));
        putZone(ZONE, 4).andExpect(status().isOk());
        mvc.perform(get("/api/v1/zones/" + ZONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.center.lat").value(42.35))
                .andExpect(jsonPath("$.radiusKm").value(4.0));
    }

    @Test
    void invalidZonesAre400() throws Exception {
        putZone("Not A Slug", 3).andExpect(status().isBadRequest());
        putZone(ZONE, 0).andExpect(status().isBadRequest());
        putZone(ZONE, 51).andExpect(status().isBadRequest());
    }

    @Test
    void unknownZoneIs404() throws Exception {
        mvc.perform(get("/api/v1/zones/nowhere")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/zones/nowhere/dispatch")).andExpect(status().isNotFound());
    }

    @Test
    void theDayTwoFlowOverHttp() throws Exception {
        putZone(ZONE, 3);
        String courier = JsonPath.read(json(post("/api/v1/couriers"),
                "{\"zoneId\": \"" + ZONE + "\", \"name\": \"Ana\"}").andReturn().getResponse().getContentAsString(),
                "$.id");
        json(put("/api/v1/couriers/" + courier + "/status"), "{\"status\": \"AVAILABLE\"}")
                .andExpect(status().isOk());
        json(put("/api/v1/couriers/" + courier + "/location"), "{\"lat\": 42.352, \"lng\": -71.08}")
                .andExpect(status().isNoContent());
        String order = JsonPath.read(json(post("/api/v1/orders").header("Idempotency-Key", "flow-1"), """
                        {"zoneId": "%s", "pickup": {"lat": 42.35, "lng": -71.08},
                         "dropoff": {"lat": 42.36, "lng": -71.06}}""".formatted(ZONE))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/v1/zones/" + ZONE + "/dispatch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offers.length()").value(1))
                .andExpect(jsonPath("$.offers[0].orderId").value(order))
                .andExpect(jsonPath("$.offers[0].courierId").value(courier));
        mvc.perform(get("/api/v1/orders/" + order)).andExpect(jsonPath("$.status").value("OFFERED"));
        mvc.perform(get("/api/v1/couriers/" + courier)).andExpect(jsonPath("$.status").value("OFFERED"));

        // The courier is busy with the offer now, so it can't slip offline.
        json(put("/api/v1/couriers/" + courier + "/status"), "{\"status\": \"OFFLINE\"}")
                .andExpect(status().isConflict());
    }
}
