package io.github.vivekdavara.dispatch.courier;

import io.github.vivekdavara.dispatch.zone.UnknownZoneException;
import io.github.vivekdavara.dispatch.zone.ZoneRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CourierService {

    private final CourierRepository couriers;
    private final ZoneRepository zones;
    private final Clock clock;

    public CourierService(CourierRepository couriers, ZoneRepository zones, Clock clock) {
        this.couriers = couriers;
        this.zones = zones;
        this.clock = clock;
    }

    public Courier register(String zoneId, String name) {
        if (zones.find(zoneId).isEmpty()) {
            throw new UnknownZoneException(zoneId);
        }
        return couriers.insert(UUID.randomUUID(), zoneId, name, clock.instant());
    }

    public Optional<Courier> find(UUID id) {
        return couriers.find(id);
    }
}
