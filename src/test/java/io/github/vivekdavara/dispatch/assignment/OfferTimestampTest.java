package io.github.vivekdavara.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vivekdavara.dispatch.courier.CourierLocations;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.support.Fixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * An offer's {@code offeredAt} is when it was claimed. With a clock that moves one second per reading, two offers
 * made in one pass must carry different times, both after the pass began, and the event's time must be exactly
 * the stored one.
 */
@SpringBootTest
@DirtiesContext
class OfferTimestampTest {

    /**
     * Starts now and moves one second every time it's read. Its readings have nanoseconds, as Linux clocks do
     * (macOS stops at microseconds), so a time that isn't truncated before Postgres rounds it can't match.
     */
    static final class TickingClock extends Clock {
        private final Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789);
        private final AtomicLong reads = new AtomicLong();

        @Override
        public Instant instant() {
            return start.plusSeconds(reads.getAndIncrement());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        Clock tickingClock() {
            return new TickingClock();
        }
    }

    @Autowired
    AssignmentEngine engine;

    @Autowired
    Clock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    CourierLocations locations;

    Fixtures f;

    @BeforeEach
    void setUp() {
        f = new Fixtures(jdbc, redis, locations, "stamp");
    }

    @AfterEach
    void tearDown() {
        f.cleanUp();
    }

    @Test
    void eachOfferIsStampedWhenItIsClaimedNotWhenThePassBegan() {
        f.availableCourier(Fixtures.north(200), Duration.ofMinutes(1));
        f.availableCourier(Fixtures.north(900), Duration.ofMinutes(1));
        f.order(OrderTier.STANDARD, Duration.ofMinutes(2));
        f.order(OrderTier.STANDARD, Duration.ofMinutes(1));
        Instant beforePass = clock.instant();

        List<Offer> offers = engine.dispatchZone(f.zoneId());

        assertThat(offers).hasSize(2);
        assertThat(offers.get(0).offeredAt()).isAfter(beforePass);
        assertThat(offers.get(1).offeredAt()).isAfter(offers.get(0).offeredAt());
        Instant stored = jdbc.queryForObject("SELECT offered_at FROM assignments WHERE id = ?",
                java.sql.Timestamp.class, offers.get(1).assignmentId()).toInstant();
        assertThat(stored).isEqualTo(offers.get(1).offeredAt());
    }
}
