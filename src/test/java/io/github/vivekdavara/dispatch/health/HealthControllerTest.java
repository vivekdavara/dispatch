package io.github.vivekdavara.dispatch.health;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Controller logic with the dependencies mocked, so failure paths are testable without breaking real servers. */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JdbcTemplate jdbc;

    @MockitoBean
    StringRedisTemplate redis;

    @Test
    void upWhenBothProbesSucceed() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(redis.execute(any(RedisCallback.class), anyBoolean())).thenReturn("PONG");
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void returns503WhenRedisIsDown() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(redis.execute(any(RedisCallback.class), eq(true)))
                .thenThrow(new RedisConnectionFailureException("refused"));
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.checks.postgres.status").value("UP"))
                .andExpect(jsonPath("$.checks.redis.status").value("DOWN"))
                .andExpect(jsonPath("$.checks.redis.error").value("RedisConnectionFailureException"));
    }

    @Test
    void returns503WhenPostgresIsDown() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("refused"));
        when(redis.execute(any(RedisCallback.class), anyBoolean())).thenReturn("PONG");
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.checks.postgres.status").value("DOWN"))
                .andExpect(jsonPath("$.checks.redis.status").value("UP"));
    }
}
