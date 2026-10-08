package io.github.vivekdavara.dispatch.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vivekdavara.dispatch.assignment.Assignment;
import io.github.vivekdavara.dispatch.assignment.AssignmentRepository;
import io.github.vivekdavara.dispatch.assignment.Offer;
import io.github.vivekdavara.dispatch.assignment.OfferClosed;
import io.github.vivekdavara.dispatch.assignment.OfferService;
import io.github.vivekdavara.dispatch.courier.CourierRepository;
import io.github.vivekdavara.dispatch.order.Order;
import io.github.vivekdavara.dispatch.order.OrderRepository;
import io.github.vivekdavara.dispatch.web.ApiErrors;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The WebSocket hub: one socket per courier at {@code /ws/couriers/{courierId}}.
 *
 * <ul>
 *   <li>The engine's {@link io.github.vivekdavara.dispatch.assignment.Offer} events are pushed to the courier's
 *       socket as {@code offer} messages; {@link OfferClosed} events as {@code offer_closed}.</li>
 *   <li>{@code accept} and {@code decline} messages go to {@link OfferService}, exactly like the HTTP endpoints,
 *       and get an {@code accepted}/{@code declined} or {@code error} reply.</li>
 *   <li>On connect the courier gets {@code hello} and then its open offer, if it has one, so an app that dropped
 *       its connection mid-offer can still answer it after reconnecting.</li>
 *   <li>A courier offered an order while not connected isn't pushed anything; the offer expires on its timeout
 *       and the order moves on. The socket is a delivery channel, not the source of truth.</li>
 *   <li>A second connection for the same courier replaces the first, which is closed with code 4001.</li>
 * </ul>
 *
 * Sessions are wrapped in {@link ConcurrentWebSocketSessionDecorator}: offers are sent from engine threads while
 * replies are sent from the socket's own thread, and a raw session can't be written by two threads at once.
 */
@Component
public class CourierSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CourierSocketHandler.class);

    static final CloseStatus REPLACED = new CloseStatus(4001, "replaced by a newer connection");
    static final CloseStatus UNKNOWN_COURIER = new CloseStatus(4004, "unknown courier");

    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_LIMIT_BYTES = 64 * 1024;
    private static final String COURIER_ID = "courierId";

    private final Map<UUID, ConcurrentWebSocketSessionDecorator> sessions = new ConcurrentHashMap<>();
    private final CourierRepository couriers;
    private final OrderRepository orders;
    private final AssignmentRepository assignments;
    private final OfferService offers;
    private final ObjectMapper json;

    public CourierSocketHandler(CourierRepository couriers, OrderRepository orders, AssignmentRepository assignments,
                                OfferService offers, ObjectMapper json) {
        this.couriers = couriers;
        this.orders = orders;
        this.assignments = assignments;
        this.offers = offers;
        this.json = json;
    }

    /** Whether the courier has an open socket right now. */
    public boolean isConnected(UUID courierId) {
        return sessions.containsKey(courierId);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws IOException {
        Optional<UUID> id = courierIdFrom(raw.getUri()).filter(c -> couriers.find(c).isPresent());
        if (id.isEmpty()) {
            raw.close(UNKNOWN_COURIER);
            return;
        }
        UUID courierId = id.get();
        raw.getAttributes().put(COURIER_ID, courierId);
        var session = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
        ConcurrentWebSocketSessionDecorator previous = sessions.put(courierId, session);
        if (previous != null) {
            previous.close(REPLACED);
        }
        send(session, new CourierMessages.Hello(courierId));
        assignments.openOfferFor(courierId).ifPresent(a -> send(session, offerMessage(a.id(), a.orderId(),
                a.distanceMeters(), a.offeredAt())));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        UUID courierId = (UUID) raw.getAttributes().get(COURIER_ID);
        if (courierId != null) {
            // Only if it's still this socket: a replaced one must not unregister its replacement.
            sessions.computeIfPresent(courierId, (c, s) -> s.getDelegate() == raw ? null : s);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        UUID courierId = (UUID) raw.getAttributes().get(COURIER_ID);
        ConcurrentWebSocketSessionDecorator session = courierId == null ? null : sessions.get(courierId);
        if (session == null || session.getDelegate() != raw) {
            return; // a refused or replaced socket's last words
        }
        CourierMessages.Inbound in;
        try {
            in = json.readValue(message.getPayload(), CourierMessages.Inbound.class);
        } catch (JsonProcessingException e) {
            send(session, new CourierMessages.ErrorMessage(null, 400, "not a JSON message"));
            return;
        }
        if (in.assignmentId() == null || in.type() == null) {
            send(session, new CourierMessages.ErrorMessage(in.assignmentId(), 400,
                    "messages need a type and an assignmentId"));
            return;
        }
        try {
            switch (in.type()) {
                case "accept" -> {
                    offers.accept(in.assignmentId(), courierId);
                    send(session, new CourierMessages.Ack("accepted", in.assignmentId()));
                }
                case "decline" -> {
                    offers.decline(in.assignmentId(), courierId);
                    send(session, new CourierMessages.Ack("declined", in.assignmentId()));
                }
                default -> send(session, new CourierMessages.ErrorMessage(in.assignmentId(), 400,
                        "unknown message type '" + in.type() + "'; expected accept or decline"));
            }
        } catch (ApiErrors.NotFoundException e) {
            send(session, new CourierMessages.ErrorMessage(in.assignmentId(), 404, e.getMessage()));
        } catch (ApiErrors.ConflictException e) {
            send(session, new CourierMessages.ErrorMessage(in.assignmentId(), 409, e.getMessage()));
        }
    }

    /** Pushes a fresh offer to its courier, if connected. Runs on the engine thread that made the claim. */
    @EventListener
    public void on(Offer offer) {
        WebSocketSession session = sessions.get(offer.courierId());
        if (session != null) {
            send(session, offerMessage(offer.assignmentId(), offer.orderId(), offer.distanceMeters(),
                    offer.offeredAt()));
        }
    }

    @EventListener
    public void on(OfferClosed closed) {
        Assignment a = closed.assignment();
        WebSocketSession session = sessions.get(a.courierId());
        if (session != null) {
            send(session, new CourierMessages.OfferClosedMessage(a.id(), a.status().name()));
        }
    }

    private CourierMessages.OfferMessage offerMessage(long assignmentId, UUID orderId, double distanceMeters,
                                                      Instant offeredAt) {
        Order o = orders.find(orderId).orElseThrow();
        return new CourierMessages.OfferMessage("offer", assignmentId, orderId, o.tier(), o.pickup(), o.dropoff(),
                distanceMeters, offeredAt, offers.expiresAt(offeredAt));
    }

    private void send(WebSocketSession session, Object message) {
        try {
            session.sendMessage(new TextMessage(json.writeValueAsString(message)));
        } catch (IOException | IllegalStateException e) {
            // A dead or slow socket. The offer stands and will expire if unanswered; nothing else to do here.
            log.debug("couldn't send to courier socket {}: {}", session.getId(), e.getMessage());
        }
    }

    /** The courier id from {@code /ws/couriers/{id}}. */
    static Optional<UUID> courierIdFrom(URI uri) {
        if (uri == null) {
            return Optional.empty();
        }
        String path = uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        try {
            return Optional.of(UUID.fromString(last));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
