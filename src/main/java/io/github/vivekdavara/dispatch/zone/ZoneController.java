package io.github.vivekdavara.dispatch.zone;

import io.github.vivekdavara.dispatch.assignment.AssignmentEngine;
import io.github.vivekdavara.dispatch.assignment.Offer;
import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/zones")
public class ZoneController {

    /** Lowercase slug ids, e.g. {@code boston-back-bay}. */
    static final String ID_PATTERN = "[a-z0-9][a-z0-9-]{0,62}";

    public record ZoneRequest(
            @NotBlank String name,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double centerLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double centerLng,
            @NotNull @Positive @DecimalMax("50") Double radiusKm) {
    }

    public record DispatchResult(String zoneId, List<Offer> offers) {
    }

    private final ZoneRepository zones;
    private final AssignmentEngine engine;

    public ZoneController(ZoneRepository zones, AssignmentEngine engine) {
        this.zones = zones;
        this.engine = engine;
    }

    /** Creates or updates a zone; PUT because the client names it and repeating it is harmless. */
    @PutMapping("/{id}")
    public Zone put(@PathVariable @Pattern(regexp = ID_PATTERN) String id, @Valid @RequestBody ZoneRequest r) {
        return zones.upsert(new Zone(id, r.name(), new GeoPoint(r.centerLat(), r.centerLng()), r.radiusKm()));
    }

    @GetMapping("/{id}")
    public Zone get(@PathVariable String id) {
        return zones.find(id).orElseThrow(() -> new ApiErrors.NotFoundException("zone " + id));
    }

    /**
     * Runs one assignment pass over the zone now and returns the offers it made. Until the event-driven loop
     * (day 3) runs passes on its own, this is how a pass is triggered; afterwards it stays as an operator tool.
     */
    @PostMapping("/{id}/dispatch")
    public DispatchResult dispatch(@PathVariable String id) {
        get(id);
        return new DispatchResult(id, engine.dispatchZone(id));
    }
}
