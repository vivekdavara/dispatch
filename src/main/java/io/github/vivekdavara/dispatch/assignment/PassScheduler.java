package io.github.vivekdavara.dispatch.assignment;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs engine passes when something happens in a zone, with two guarantees:
 *
 * <ul>
 *   <li><b>One pass per zone at a time.</b> Passes over the same zone would compete for the same orders and
 *       couriers; they'd be correct (the claims are compare-and-sets) but wasteful. Different zones run in
 *       parallel on the executor.</li>
 *   <li><b>Requests coalesce, and none is lost.</b> A burst of 1,000 new orders doesn't queue 1,000 passes: a
 *       request while a pass is running only marks the zone dirty, and the running pass loops once more when it
 *       finishes. So every request is followed by at least one pass that <i>started after</i> it, which is what
 *       matters: that pass sees the new order or the newly free courier.</li>
 * </ul>
 *
 * <p>Per zone, a tiny state machine in one {@link AtomicInteger}: IDLE, RUNNING, or RUNNING_AGAIN (running, and a
 * request arrived since it started). A request moves IDLE to RUNNING (and submits) or RUNNING to RUNNING_AGAIN
 * (and returns). A pass that finishes moves RUNNING to IDLE, or RUNNING_AGAIN to RUNNING and runs again.
 */
public class PassScheduler {

    private static final Logger log = LoggerFactory.getLogger(PassScheduler.class);

    static final int IDLE = 0;
    static final int RUNNING = 1;
    static final int RUNNING_AGAIN = 2;

    private final Executor executor;
    private final Consumer<String> pass;
    private final ConcurrentMap<String, AtomicInteger> zones = new ConcurrentHashMap<>();

    /** @param pass one engine pass over a zone; exceptions are logged and don't stop later passes */
    public PassScheduler(Executor executor, Consumer<String> pass) {
        this.executor = executor;
        this.pass = pass;
    }

    /** Asks for a pass over {@code zoneId} soon. Never blocks and never runs the pass on the caller's thread. */
    public void request(String zoneId) {
        AtomicInteger state = zones.computeIfAbsent(zoneId, z -> new AtomicInteger(IDLE));
        while (true) {
            int s = state.get();
            if (s == IDLE && state.compareAndSet(IDLE, RUNNING)) {
                submit(zoneId, state);
                return;
            }
            if (s == RUNNING && state.compareAndSet(RUNNING, RUNNING_AGAIN)) {
                return;
            }
            if (s == RUNNING_AGAIN) {
                return; // a pass that starts after this request is already promised
            }
            // Lost a race with another request or with the finishing pass: read again.
        }
    }

    /** The zone's state, for tests and diagnostics. */
    int state(String zoneId) {
        AtomicInteger s = zones.get(zoneId);
        return s == null ? IDLE : s.get();
    }

    private void submit(String zoneId, AtomicInteger state) {
        try {
            executor.execute(() -> runUntilClean(zoneId, state));
        } catch (RejectedExecutionException e) {
            // Shutting down. Leave the zone requestable rather than stuck in RUNNING.
            state.set(IDLE);
            log.warn("dispatch pass for zone {} rejected: {}", zoneId, e.getMessage());
        }
    }

    private void runUntilClean(String zoneId, AtomicInteger state) {
        while (true) {
            try {
                pass.accept(zoneId);
            } catch (RuntimeException e) {
                // One bad pass (say, Redis briefly down) must not wedge the zone; the next request or the
                // periodic sweep tries again.
                log.warn("dispatch pass for zone {} failed", zoneId, e);
            }
            if (state.compareAndSet(RUNNING, IDLE)) {
                return;
            }
            // RUNNING_AGAIN: a request came in while we were working. Run once more for it.
            state.set(RUNNING);
        }
    }
}
