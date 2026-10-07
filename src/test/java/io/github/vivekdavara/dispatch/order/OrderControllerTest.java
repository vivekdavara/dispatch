package io.github.vivekdavara.dispatch.order;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

/** The HTTP contract of order intake, against real Postgres; rows are rolled back after each test. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderControllerTest {

    static final String BODY = """
            {"zoneId": "api-zone",
             "pickup": {"lat": 42.35, "lng": -71.08},
             "dropoff": {"lat": 42.36, "lng": -71.06},
             "tier": "PRIORITY"}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void zone() {
        jdbc.update("INSERT INTO zones (id, name, center_lat, center_lng, radius_km) VALUES (?, ?, ?, ?, ?)",
                "api-zone", "API test zone", 42.35, -71.08, 3.0);
    }

    ResultActions create(String key, String body) throws Exception {
        var req = MockMvcRequestBuilders.post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    @Test
    void createReturns201WithTheOrder() throws Exception {
        create("k-1", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/orders/")))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.tier").value("PRIORITY"))
                .andExpect(jsonPath("$.pickup.lat").value(42.35))
                .andExpect(jsonPath("$.zoneId").value("api-zone"));
    }

    @Test
    void retryReturns200WithTheSameOrder() throws Exception {
        String first = create("k-1", BODY).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(first, "$.id");
        create("k-1", BODY)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void reusedKeyWithDifferentBodyIs409() throws Exception {
        create("k-1", BODY).andExpect(status().isCreated());
        create("k-1", BODY.replace("PRIORITY", "STANDARD"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(startsWith("Idempotency-Key 'k-1' was already used")));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        create(null, BODY)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void idempotencyKeyWithSpacesIs400() throws Exception {
        create("has space", BODY).andExpect(status().isBadRequest());
    }

    @Test
    void outOfRangeCoordinateIs400() throws Exception {
        create("k-1", BODY.replace("\"lat\": 42.36", "\"lat\": 91")).andExpect(status().isBadRequest());
    }

    @Test
    void missingDropoffIs400() throws Exception {
        create("k-1", """
                {"zoneId": "api-zone", "pickup": {"lat": 42.35, "lng": -71.08}}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownTierIs400() throws Exception {
        create("k-1", BODY.replace("PRIORITY", "PLATINUM")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownZoneIs422() throws Exception {
        create("k-1", BODY.replace("api-zone", "mars"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("unknown zone: mars"));
    }

    @Test
    void pickupOutsideZoneIs422() throws Exception {
        create("k-1", BODY.replace("\"lat\": 42.35", "\"lat\": 42.45")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getReturnsTheOrder() throws Exception {
        String id = JsonPath.read(create("k-1", BODY).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/v1/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void getUnknownOrderIs404() throws Exception {
        mvc.perform(get("/api/v1/orders/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void getWithMalformedIdIs400() throws Exception {
        mvc.perform(get("/api/v1/orders/not-a-uuid")).andExpect(status().isBadRequest());
    }
}
