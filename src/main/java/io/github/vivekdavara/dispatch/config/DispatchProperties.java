package io.github.vivekdavara.dispatch.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tunables under {@code dispatch.*} in application.yml. */
@ConfigurationProperties("dispatch")
public record DispatchProperties(@DefaultValue Locations locations, @DefaultValue Assignment assignment,
                                 @DefaultValue Offers offers) {

    /**
     * @param freshFor how long a location ping keeps a courier eligible; a courier with no ping for longer is
     *                 treated as disconnected
     */
    public record Locations(@DefaultValue("60s") Duration freshFor) {
    }

    /**
     * @param maxPickupKm  candidates must be at most this far from the pickup
     * @param pendingBatch how many of a zone's oldest pending orders one engine pass considers
     */
    public record Assignment(@DefaultValue("5.0") double maxPickupKm, @DefaultValue("200") int pendingBatch) {
    }

    /**
     * @param timeout how long a courier has to answer an offer before it expires and the order is re-offered
     */
    public record Offers(@DefaultValue("30s") Duration timeout) {
    }
}
