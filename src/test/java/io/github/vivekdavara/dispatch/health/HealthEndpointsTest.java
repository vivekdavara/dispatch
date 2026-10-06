package io.github.vivekdavara.dispatch.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Against the real Postgres and Redis: both must be up. */
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void reportsUpWhenPostgresAndRedisAnswer() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.checks.postgres.status").value("UP"))
                .andExpect(jsonPath("$.checks.redis.status").value("UP"));
    }

    @Test
    void actuatorHealthIncludesDatabaseAndRedis() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }
}
