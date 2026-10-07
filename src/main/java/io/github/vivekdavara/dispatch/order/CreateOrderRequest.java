package io.github.vivekdavara.dispatch.order;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.domain.OrderTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/v1/orders}. A missing tier means STANDARD. */
public record CreateOrderRequest(
        @NotBlank String zoneId,
        @NotNull @Valid Location pickup,
        @NotNull @Valid Location dropoff,
        OrderTier tier) {

    public record Location(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {

        public GeoPoint toGeoPoint() {
            return new GeoPoint(lat, lng);
        }
    }

    /** The tier the order will get: the requested one, or STANDARD when omitted. */
    public OrderTier effectiveTier() {
        return tier == null ? OrderTier.STANDARD : tier;
    }
}
