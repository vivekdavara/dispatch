package io.github.vivekdavara.dispatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/**
 * Decides which pending order is served next: {@code tierBonus + minutesWaiting}, highest first.
 *
 * <p>A PRIORITY order counts as if it had already waited 10 extra minutes. Waiting time keeps growing, so a
 * STANDARD order can't be starved: after 10 minutes it outranks any newly created PRIORITY order.
 */
public final class PriorityScore {

    private PriorityScore() {
    }

    /** The fields of an order the rule looks at. */
    public record Candidate(UUID orderId, OrderTier tier, Instant createdAt) {
    }

    /** Score of an order at {@code now}; orders created "in the future" (clock skew) count as zero wait. */
    public static double score(OrderTier tier, Instant createdAt, Instant now) {
        long waitedMillis = Math.max(0, Duration.between(createdAt, now).toMillis());
        return tier.bonusMinutes() + waitedMillis / 60_000.0;
    }

    /**
     * Total order over pending orders at {@code now}: highest score first, then oldest, then smallest id, so
     * the engine's choice is deterministic.
     */
    public static Comparator<Candidate> servingOrder(Instant now) {
        return Comparator
                .comparingDouble((Candidate c) -> score(c.tier(), c.createdAt(), now)).reversed()
                .thenComparing(Candidate::createdAt)
                .thenComparing(Candidate::orderId);
    }
}
