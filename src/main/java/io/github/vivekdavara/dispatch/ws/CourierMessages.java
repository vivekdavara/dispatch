package io.github.vivekdavara.dispatch.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import java.time.Instant;
import java.util.UUID;

/**
 * The JSON messages on {@code /ws/couriers/{id}}. Every message has a {@code type}.
 *
 * <p>Server to courier: {@code hello}, {@code offer}, {@code offer_closed}, {@code accepted}, {@code declined},
 * {@code error}. Courier to server: {@code accept} and {@code decline}, each with an {@code assignmentId}.
 */
public final class CourierMessages {

    private CourierMessages() {
    }

    /** What a courier sends. */
    public record Inbound(String type, Long assignmentId) {
    }

    /** Sent once the socket is open. */
    public record Hello(String type, UUID courierId) {
        public Hello(UUID courierId) {
            this("hello", courierId);
        }
    }

    /** A new offer (or, right after connecting, the courier's still-open one). Answer before {@code expiresAt}. */
    public record OfferMessage(String type, long assignmentId, UUID orderId, OrderTier tier, GeoPoint pickup,
                               GeoPoint dropoff, double distanceMeters, Instant offeredAt, Instant expiresAt) {
    }

    /** The offer ended: {@code status} is ACCEPTED, DECLINED or EXPIRED. */
    public record OfferClosedMessage(String type, long assignmentId, String status) {
        public OfferClosedMessage(long assignmentId, String status) {
            this("offer_closed", assignmentId, status);
        }
    }

    /** The reply to an accept or decline that worked ({@code type} is {@code accepted} or {@code declined}). */
    public record Ack(String type, long assignmentId) {
    }

    /** The reply to a message that didn't work; {@code status} uses HTTP codes (400, 404, 409). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorMessage(String type, Long assignmentId, int status, String detail) {
        public ErrorMessage(Long assignmentId, int status, String detail) {
            this("error", assignmentId, status, detail);
        }
    }
}
