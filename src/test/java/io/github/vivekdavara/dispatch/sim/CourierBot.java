package io.github.vivekdavara.dispatch.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One simulated courier app: holds the courier's socket and answers offers the way a person would.
 *
 * <ul>
 *   <li>An offer is answered after a think time: declined with probability {@code declineShare}, otherwise
 *       accepted. With probability {@code answerRetryShare} the answer is sent twice (an app that didn't see the
 *       acknowledgement and retried); both must be acknowledged.</li>
 *   <li>An accepted order is picked up and delivered over HTTP after simulated travel times.</li>
 *   <li>{@link #drop} closes the socket without a goodbye (lost network); {@link #reconnect} opens a new one and,
 *       if the server took the courier offline meanwhile, puts it back online.</li>
 * </ul>
 */
final class CourierBot implements WebSocket.Listener {

    /** The bot's timing and behaviour, in simulated milliseconds (divided by the replay speed). */
    record Behaviour(double declineShare, double answerRetryShare, long thinkMinMs, long thinkMaxMs,
                     long pickupMinMs, long pickupMaxMs, long deliverMinMs, long deliverMaxMs) {

        /** The 50K run: answers within a second, picks up in 1-3 s, delivers 2-6 s later. */
        static Behaviour standard() {
            return new Behaviour(0.10, 0.05, 200, 1_000, 1_000, 3_000, 2_000, 6_000);
        }

        /** The small scenario: everything about ten times faster, so 16 couriers can carry 200 orders. */
        static Behaviour quick() {
            return new Behaviour(0.10, 0.10, 20, 100, 100, 300, 200, 600);
        }
    }

    final int index;
    final String courierId;
    private final DispatchApi api;
    private final ObjectMapper json;
    private final Recorder rec;
    private final ScheduledExecutorService timers;
    private final Behaviour behaviour;
    private final double speed;
    private final Random rnd;

    /** Assignment id to order id, for every offer seen. */
    private final Map<Long, String> orderOf = new ConcurrentHashMap<>();
    private final Set<Long> answered = ConcurrentHashMap.newKeySet();
    private final Set<Long> acked = ConcurrentHashMap.newKeySet();
    private final Set<Long> delivering = ConcurrentHashMap.newKeySet();
    private final StringBuilder partial = new StringBuilder();

    private volatile WebSocket socket;
    /** java.net.http allows one outstanding send per socket, so sends are chained. */
    private CompletableFuture<?> sends = CompletableFuture.completedFuture(null);
    private volatile boolean reconnecting;

    CourierBot(int index, String courierId, DispatchApi api, ObjectMapper json, Recorder rec,
               ScheduledExecutorService timers, Behaviour behaviour, double speed, long seed) {
        this.index = index;
        this.courierId = courierId;
        this.api = api;
        this.json = json;
        this.rec = rec;
        this.timers = timers;
        this.behaviour = behaviour;
        this.speed = speed;
        this.rnd = new Random(seed * 31 + index);
    }

    void connect() {
        socket = api.connect(courierId, this).join();
    }

    boolean connected() {
        return socket != null;
    }

    /** The network goes away: no close handshake, the server just sees the connection die. */
    void drop() {
        WebSocket s = socket;
        socket = null;
        if (s != null) {
            s.abort();
            rec.drops.increment();
        }
    }

    void reconnect() {
        reconnecting = true;
        api.connect(courierId, this).whenComplete((s, e) -> {
            if (e != null) {
                rec.socketErrors.increment();
                rec.note("courier " + index + " reconnect failed: " + e);
                timers.schedule(this::reconnect, 500, TimeUnit.MILLISECONDS);
            } else {
                socket = s;
                rec.reconnects.increment();
            }
        });
    }

    void close() {
        WebSocket s = socket;
        socket = null;
        if (s != null) {
            s.abort();
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        synchronized (partial) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                try {
                    handle(json.readTree(message));
                } catch (IOException | RuntimeException e) {
                    rec.socketErrors.increment();
                    rec.note("courier " + index + " couldn't handle " + message + ": " + e);
                }
            }
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        if (ws == socket) {
            socket = null;
        }
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        if (ws == socket) {
            socket = null;
        }
    }

    private void handle(JsonNode m) {
        long now = System.nanoTime();
        long assignmentId = m.path("assignmentId").asLong(-1);
        switch (m.path("type").asText()) {
            case "hello" -> {
                if (reconnecting) {
                    reconnecting = false;
                    // A long outage gets the courier taken offline; come back. 409 (offered or busy) is fine.
                    api.callAsync("PUT", "/api/v1/couriers/" + courierId + "/status", Map.of("status", "AVAILABLE"));
                }
            }
            case "offer" -> {
                String orderId = m.path("orderId").asText();
                rec.firstOfferAt.putIfAbsent(orderId, now);
                if (orderOf.putIfAbsent(assignmentId, orderId) == null) {
                    rec.offers.increment();
                } else {
                    rec.offersResent.increment(); // resent after a reconnect
                }
                if (!answered.contains(assignmentId)) {
                    timers.schedule(() -> answer(assignmentId), scaled(between(behaviour.thinkMinMs(),
                            behaviour.thinkMaxMs())), TimeUnit.MICROSECONDS);
                }
            }
            case "accepted" -> {
                acked.add(assignmentId);
                String orderId = orderOf.get(assignmentId);
                if (orderId != null && delivering.add(assignmentId)) {
                    rec.acceptedAt.putIfAbsent(orderId, now);
                    long pickupIn = scaled(between(behaviour.pickupMinMs(), behaviour.pickupMaxMs()));
                    long deliverIn = pickupIn + scaled(between(behaviour.deliverMinMs(), behaviour.deliverMaxMs()));
                    timers.schedule(() -> post(assignmentId, "pickup", null), pickupIn, TimeUnit.MICROSECONDS);
                    timers.schedule(() -> post(assignmentId, "deliver", orderId), deliverIn, TimeUnit.MICROSECONDS);
                }
            }
            case "declined" -> acked.add(assignmentId);
            case "offer_closed" -> {
                if ("EXPIRED".equals(m.path("status").asText())) {
                    rec.expiredSeen.increment();
                }
            }
            case "error" -> {
                if (acked.contains(assignmentId)) {
                    rec.answerRetriesRejected.increment(); // this answer already worked; its retry was refused
                } else if (m.path("status").asInt() == 409) {
                    rec.answersRefused.increment(); // the offer closed before the answer got there
                } else {
                    rec.socketErrors.increment();
                    rec.note("courier " + index + " got " + m);
                }
            }
            default -> {
                rec.socketErrors.increment();
                rec.note("courier " + index + " got " + m);
            }
        }
    }

    private void answer(long assignmentId) {
        if (socket == null || !answered.add(assignmentId)) {
            return; // the socket is down (the offer will be resent or released), or already answered
        }
        boolean decline;
        boolean retry;
        synchronized (rnd) {
            decline = rnd.nextDouble() < behaviour.declineShare();
            retry = rnd.nextDouble() < behaviour.answerRetryShare();
        }
        (decline ? rec.declines : rec.accepts).increment();
        String type = decline ? "decline" : "accept";
        String message = "{\"type\":\"" + type + "\",\"assignmentId\":" + assignmentId + "}";
        send(message);
        if (retry) {
            rec.answerRetries.increment();
            timers.schedule(() -> send(message), 20, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void send(String text) {
        WebSocket s = socket;
        if (s == null) {
            return;
        }
        sends = sends.handle((v, e) -> null).thenCompose(v -> s.sendText(text, true))
                .exceptionally(e -> {
                    // The socket died under the send (a drop); the offer is resent or released server-side.
                    rec.sendsLost.increment();
                    return null;
                });
    }

    private void post(long assignmentId, String what, String deliveredOrderId) {
        api.callAsync("POST", "/api/v1/assignments/" + assignmentId + "/" + what, Map.of("courierId", courierId))
                .whenComplete((status, e) -> {
                    if (e != null || status != 200) {
                        rec.deliveryErrors.increment();
                        rec.note("courier " + index + " " + what + " of assignment " + assignmentId + ": "
                                + (e != null ? e.toString() : "HTTP " + status));
                    } else if (deliveredOrderId != null) {
                        rec.deliveredAt.putIfAbsent(deliveredOrderId, System.nanoTime());
                    }
                });
    }

    private long between(long min, long max) {
        synchronized (rnd) {
            return min + (long) (rnd.nextDouble() * (max - min));
        }
    }

    /** Simulated milliseconds to wall-clock microseconds at the replay speed. */
    private long scaled(long simMs) {
        return (long) (simMs * 1000 / speed);
    }
}
