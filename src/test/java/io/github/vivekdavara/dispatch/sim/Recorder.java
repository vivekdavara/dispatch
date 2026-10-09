package io.github.vivekdavara.dispatch.sim;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * What happened during a run, written from many threads (the replay thread, HTTP callbacks, socket listeners).
 * Times are {@link System#nanoTime()} readings from this one JVM, so differences between them are exact.
 */
final class Recorder {

    /** Order number to when its first POST was sent: the moment the customer pressed the button. */
    final Map<Integer, Long> orderSentAt = new ConcurrentHashMap<>();
    /** Order number to the order id the API returned. */
    final Map<Integer, String> orderIdOf = new ConcurrentHashMap<>();
    /** Order number to the response of each POST (the first, and the retry if there was one). */
    final Map<Integer, DispatchApi.OrderResponse> firstResponse = new ConcurrentHashMap<>();
    final Map<Integer, DispatchApi.OrderResponse> retryResponse = new ConcurrentHashMap<>();
    /** Order id to when the first offer for it reached any courier's socket. */
    final Map<String, Long> firstOfferAt = new ConcurrentHashMap<>();
    /** Order id to when a courier's accept was acknowledged. */
    final Map<String, Long> acceptedAt = new ConcurrentHashMap<>();
    /** Order ids delivered. */
    final Map<String, Long> deliveredAt = new ConcurrentHashMap<>();
    /**
     * For each delivery, how long until that courier's next offer arrived: from sending the delivery (the courier
     * is free once it commits) to the next offer on its socket. With orders waiting, this is the engine's reaction
     * time; with none waiting, it's mostly waiting for demand.
     */
    final Queue<Long> freedToNextOffer = new ConcurrentLinkedQueue<>();

    final LongAdder pingsSent = new LongAdder();
    final LongAdder pingErrors = new LongAdder();
    final LongAdder orderErrors = new LongAdder();
    final LongAdder offers = new LongAdder();
    final LongAdder offersResent = new LongAdder();
    final LongAdder accepts = new LongAdder();
    final LongAdder declines = new LongAdder();
    final LongAdder expiredSeen = new LongAdder();
    final LongAdder answerRetries = new LongAdder();
    final LongAdder answerRetriesRejected = new LongAdder();
    final LongAdder answersRefused = new LongAdder();
    final LongAdder socketErrors = new LongAdder();
    final LongAdder sendsLost = new LongAdder();
    final LongAdder deliveryErrors = new LongAdder();
    final LongAdder drops = new LongAdder();
    final LongAdder reconnects = new LongAdder();
    final LongAdder takenOffline = new LongAdder();
    final AtomicLong maxReplayLagNanos = new AtomicLong();
    /** The first few unexpected things, verbatim, so a report explains its own error counts. */
    private final Queue<String> notes = new ConcurrentLinkedQueue<>();

    void note(String what) {
        if (notes.size() < 20) {
            notes.add(what);
        }
    }

    List<String> notes() {
        return List.copyOf(notes);
    }

    void replayLag(long nanos) {
        maxReplayLagNanos.accumulateAndGet(nanos, Math::max);
    }

    /** Latencies from the first POST of each order to {@code reached} (orders that never got there are left out). */
    long[] sinceOrderSent(Map<String, Long> reached) {
        return orderIdOf.entrySet().stream()
                .filter(e -> reached.containsKey(e.getValue()) && orderSentAt.containsKey(e.getKey()))
                .mapToLong(e -> reached.get(e.getValue()) - orderSentAt.get(e.getKey()))
                .toArray();
    }
}
