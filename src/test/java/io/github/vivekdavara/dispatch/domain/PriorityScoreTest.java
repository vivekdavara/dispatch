package io.github.vivekdavara.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PriorityScoreTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void scoreIsTierBonusPlusMinutesWaiting() {
        assertThat(PriorityScore.score(OrderTier.STANDARD, NOW.minusSeconds(90), NOW)).isCloseTo(1.5, within(1e-9));
        assertThat(PriorityScore.score(OrderTier.PRIORITY, NOW.minusSeconds(90), NOW)).isCloseTo(11.5, within(1e-9));
    }

    @Test
    void futureCreationTimeCountsAsNoWait() {
        assertThat(PriorityScore.score(OrderTier.STANDARD, NOW.plusSeconds(30), NOW)).isEqualTo(0.0);
    }

    @Test
    void newPriorityOrderBeatsRecentStandardOrder() {
        var standard = candidate(OrderTier.STANDARD, NOW.minusSeconds(5 * 60));
        var priority = candidate(OrderTier.PRIORITY, NOW);
        assertThat(sorted(standard, priority)).containsExactly(priority, standard);
    }

    @Test
    void standardOrderIsNotStarvedAfterTenMinutes() {
        var standard = candidate(OrderTier.STANDARD, NOW.minusSeconds(11 * 60));
        var priority = candidate(OrderTier.PRIORITY, NOW);
        assertThat(sorted(priority, standard)).containsExactly(standard, priority);
    }

    @Test
    void equalScoresGoToTheOlderOrder() {
        // Standard waiting 10 min and a priority order created just now both score 10.
        var olderStandard = candidate(OrderTier.STANDARD, NOW.minusSeconds(10 * 60));
        var newPriority = candidate(OrderTier.PRIORITY, NOW);
        assertThat(sorted(newPriority, olderStandard)).containsExactly(olderStandard, newPriority);
    }

    @Test
    void identicalOrdersAreOrderedById() {
        var a = new PriorityScore.Candidate(new UUID(0, 1), OrderTier.STANDARD, NOW);
        var b = new PriorityScore.Candidate(new UUID(0, 2), OrderTier.STANDARD, NOW);
        assertThat(sorted(b, a)).containsExactly(a, b);
    }

    private static PriorityScore.Candidate candidate(OrderTier tier, Instant createdAt) {
        return new PriorityScore.Candidate(UUID.randomUUID(), tier, createdAt);
    }

    private static List<PriorityScore.Candidate> sorted(PriorityScore.Candidate... cs) {
        List<PriorityScore.Candidate> list = new ArrayList<>(List.of(cs));
        list.sort(PriorityScore.servingOrder(NOW));
        return list;
    }
}
