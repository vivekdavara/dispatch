package io.github.vivekdavara.dispatch.courier;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.web.ApiErrors;
import io.github.vivekdavara.dispatch.zone.UnknownZoneException;
import io.github.vivekdavara.dispatch.zone.ZoneRepository;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class CourierService {

    /** CAS retries for a status change that keeps losing races; in practice the first or second try wins. */
    private static final int MAX_STATUS_ATTEMPTS = 5;

    private final CourierRepository couriers;
    private final CourierLocations locations;
    private final ZoneRepository zones;
    private final Clock clock;

    /**
     * Courier id to zone id. A courier's zone never changes, so after the first ping a location update is Redis
     * only. Bounded by the number of couriers, which is small next to the number of pings.
     */
    private final Map<UUID, String> zoneOf = new ConcurrentHashMap<>();

    public CourierService(CourierRepository couriers, CourierLocations locations, ZoneRepository zones,
                          Clock clock) {
        this.couriers = couriers;
        this.locations = locations;
        this.zones = zones;
        this.clock = clock;
    }

    public Courier register(String zoneId, String name) {
        if (zones.find(zoneId).isEmpty()) {
            throw new UnknownZoneException(zoneId);
        }
        Courier c = couriers.insert(UUID.randomUUID(), zoneId, name, clock.instant());
        zoneOf.put(c.id(), c.zoneId());
        return c;
    }

    public Optional<Courier> find(UUID id) {
        return couriers.find(id);
    }

    /** A location ping: Redis only once the courier's zone is cached. */
    public void updateLocation(UUID courierId, GeoPoint at) {
        locations.update(zoneFor(courierId), courierId, at);
    }

    /**
     * A courier going online ({@code AVAILABLE}) or offline ({@code OFFLINE}). Repeating the current status is a
     * no-op. OFFERED and BUSY couriers can't change status this way: they have to answer the offer or finish the
     * delivery first.
     */
    public Courier setStatus(UUID courierId, CourierStatus target) {
        if (target != CourierStatus.AVAILABLE && target != CourierStatus.OFFLINE) {
            throw new ApiErrors.UnprocessableException("couriers can only set AVAILABLE or OFFLINE; " + target
                    + " is set by the assignment engine");
        }
        for (int attempt = 0; attempt < MAX_STATUS_ATTEMPTS; attempt++) {
            Courier current = couriers.find(courierId)
                    .orElseThrow(() -> new ApiErrors.NotFoundException("courier " + courierId));
            if (current.status() == target) {
                return current;
            }
            if (current.status() == CourierStatus.OFFERED || current.status() == CourierStatus.BUSY) {
                throw new ApiErrors.ConflictException("courier " + courierId + " is " + current.status()
                        + " and can't go " + target + " until that finishes");
            }
            if (couriers.transition(courierId, current.status(), target, clock.instant())) {
                if (target == CourierStatus.OFFLINE) {
                    locations.remove(current.zoneId(), courierId);
                }
                return couriers.find(courierId).orElseThrow();
            }
            // Lost a race (e.g. the engine just offered this courier an order): re-read and decide again.
        }
        throw new ApiErrors.ConflictException("courier " + courierId + " status is changing too fast; retry");
    }

    private String zoneFor(UUID courierId) {
        String cached = zoneOf.get(courierId);
        if (cached != null) {
            return cached;
        }
        String zone = couriers.find(courierId)
                .orElseThrow(() -> new ApiErrors.NotFoundException("courier " + courierId))
                .zoneId();
        zoneOf.put(courierId, zone);
        return zone;
    }
}
