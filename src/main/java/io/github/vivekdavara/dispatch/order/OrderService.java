package io.github.vivekdavara.dispatch.order;

import io.github.vivekdavara.dispatch.assignment.DispatchNeeded;
import io.github.vivekdavara.dispatch.zone.UnknownZoneException;
import io.github.vivekdavara.dispatch.zone.Zone;
import io.github.vivekdavara.dispatch.zone.ZoneRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Idempotent order creation. The first request with a key creates the order; a retry with the same key and the
 * same body gets that order back; the same key with a different body is an error.
 */
@Service
public class OrderService {

    /** Outcome of a create call: the order, and whether this call created it or replayed an earlier one. */
    public record Created(Order order, boolean replayed) {
    }

    public static class IdempotencyKeyReusedException extends RuntimeException {
        public IdempotencyKeyReusedException(String key) {
            super("Idempotency-Key '" + key + "' was already used with a different request body");
        }
    }

    public static class PickupOutsideZoneException extends RuntimeException {
        public PickupOutsideZoneException(String zoneId) {
            super("pickup is outside zone " + zoneId);
        }
    }

    private final OrderRepository orders;
    private final ZoneRepository zones;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orders, ZoneRepository zones, Clock clock, ApplicationEventPublisher events) {
        this.orders = orders;
        this.zones = zones;
        this.clock = clock;
        this.events = events;
    }

    public Created create(String idempotencyKey, CreateOrderRequest request) {
        String hash = RequestHash.of(request);

        // Fast path for retries: no zone lookup, no insert attempt.
        Optional<OrderRepository.Stored> existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(idempotencyKey, hash, existing.get());
        }

        Zone zone = zones.find(request.zoneId()).orElseThrow(() -> new UnknownZoneException(request.zoneId()));
        if (!zone.contains(request.pickup().toGeoPoint())) {
            throw new PickupOutsideZoneException(zone.id());
        }

        Optional<Order> inserted = orders.insertIfAbsent(UUID.randomUUID(), idempotencyKey, hash, request,
                clock.instant());
        if (inserted.isPresent()) {
            // The insert has committed (no surrounding transaction), so a pass can see the order. Replays don't
            // publish: the original request already did.
            events.publishEvent(new DispatchNeeded(zone.id(), DispatchNeeded.Reason.ORDER_CREATED));
            return new Created(inserted.get(), false);
        }
        // A concurrent request with the same key committed between our lookup and our insert.
        OrderRepository.Stored winner = orders.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("idempotency key conflict but no row: " + idempotencyKey));
        return replay(idempotencyKey, hash, winner);
    }

    public Optional<Order> find(UUID id) {
        return orders.find(id);
    }

    private static Created replay(String key, String hash, OrderRepository.Stored stored) {
        if (!stored.requestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        return new Created(stored.order(), true);
    }
}
