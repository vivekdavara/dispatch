package io.github.vivekdavara.dispatch.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tunables under {@code dispatch.*} in application.yml. */
@ConfigurationProperties("dispatch")
public record DispatchProperties(@DefaultValue Locations locations, @DefaultValue Assignment assignment,
                                 @DefaultValue Offers offers, @DefaultValue Loop loop,
                                 @DefaultValue Sockets sockets) {

    /**
     * @param freshFor how long a location ping keeps a courier eligible; a courier with no ping for longer is
     *                 treated as disconnected
     */
    public record Locations(@DefaultValue("60s") Duration freshFor) {
    }

    /**
     * @param maxPickupKm       candidates must be at most this far from the pickup
     * @param pendingBatch      how many of a zone's oldest pending orders one engine pass considers
     * @param candidateSnapshot true: read the zone's AVAILABLE couriers once per pass (and stop when none are
     *                          left); false: the original one-query-per-order pass, kept for the before/after
     *                          measurement
     */
    public record Assignment(@DefaultValue("5.0") double maxPickupKm, @DefaultValue("200") int pendingBatch,
                             @DefaultValue("true") boolean candidateSnapshot) {
    }

    /**
     * @param timeout how long a courier has to answer an offer before it expires and the order is re-offered
     */
    public record Offers(@DefaultValue("30s") Duration timeout) {
    }

    /**
     * The event-driven loop ({@code DispatchLoop}). The intervals are read by its {@code @Scheduled} methods.
     *
     * @param enabled        false turns the loop off entirely (passes then run only on the manual endpoint)
     * @param threads        passes over different zones run in parallel on this many threads
     * @param expiryInterval how often overdue offers are expired
     * @param sweepInterval  how often zones with pending orders get a pass even without an event
     */
    public record Loop(@DefaultValue("true") boolean enabled, @DefaultValue("4") int threads,
                       @DefaultValue("1s") Duration expiryInterval, @DefaultValue("5s") Duration sweepInterval) {
    }

    /**
     * Courier sockets ({@code CourierSocketHandler}).
     *
     * @param reconnectGrace how long a courier whose socket closed has to reconnect before they're taken offline
     *                       (and an offer they hold is released to the next courier)
     */
    public record Sockets(@DefaultValue("10s") Duration reconnectGrace) {
    }
}
