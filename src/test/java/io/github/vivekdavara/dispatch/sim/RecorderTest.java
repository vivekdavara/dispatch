package io.github.vivekdavara.dispatch.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** How the simulator turns its timestamps into latencies. */
class RecorderTest {

    @Test
    void latencyIsFromTheFirstPostToTheFirstOfferJoinedByOrderId() {
        Recorder rec = new Recorder();
        rec.orderSentAt.put(0, 1_000L);
        rec.orderSentAt.put(1, 2_000L);
        // Order 1's offer reached the courier before its POST's response told us its id: the join at the end
        // still pairs them.
        rec.firstOfferAt.put("order-b", 2_700L);
        rec.orderIdOf.put(1, "order-b");
        rec.orderIdOf.put(0, "order-a");
        rec.firstOfferAt.put("order-a", 1_500L);

        assertThat(rec.sinceOrderSent(rec.firstOfferAt)).containsExactlyInAnyOrder(500L, 700L);
    }

    @Test
    void ordersThatNeverGotThereAreLeftOutNotCountedAsZero() {
        Recorder rec = new Recorder();
        rec.orderSentAt.put(0, 1_000L);
        rec.orderIdOf.put(0, "never-offered");
        rec.orderSentAt.put(1, 1_000L); // its POST failed: no order id

        assertThat(rec.sinceOrderSent(rec.firstOfferAt)).isEmpty();
    }

    @Test
    void notesKeepOnlyTheFirstTwenty() {
        Recorder rec = new Recorder();
        for (int i = 0; i < 25; i++) {
            rec.note("n" + i);
        }

        assertThat(rec.notes()).hasSize(20).startsWith("n0").endsWith("n19");
    }

    @Test
    void replayLagKeepsTheWorst() {
        Recorder rec = new Recorder();
        rec.replayLag(5);
        rec.replayLag(50);
        rec.replayLag(7);

        assertThat(rec.maxReplayLagNanos.get()).isEqualTo(50);
    }
}
