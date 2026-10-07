package io.github.vivekdavara.dispatch.courier;

import io.github.vivekdavara.dispatch.domain.GeoPoint;
import io.github.vivekdavara.dispatch.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/couriers")
public class CourierController {

    public record RegisterRequest(@NotBlank String zoneId, @NotBlank @Size(max = 200) String name) {
    }

    public record LocationRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }

    public record StatusRequest(@NotNull CourierStatus status) {
    }

    private final CourierService service;

    public CourierController(CourierService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Courier> register(@Valid @RequestBody RegisterRequest request) {
        Courier c = service.register(request.zoneId(), request.name());
        return ResponseEntity.created(URI.create("/api/v1/couriers/" + c.id())).body(c);
    }

    @GetMapping("/{id}")
    public Courier get(@PathVariable UUID id) {
        return service.find(id).orElseThrow(() -> new ApiErrors.NotFoundException("courier " + id));
    }

    /** A location ping, sent every few seconds by the courier app. 204: there's nothing to return. */
    @PutMapping("/{id}/location")
    public ResponseEntity<Void> location(@PathVariable UUID id, @Valid @RequestBody LocationRequest request) {
        service.updateLocation(id, new GeoPoint(request.lat(), request.lng()));
        return ResponseEntity.noContent().build();
    }

    /** Going online (AVAILABLE) or offline (OFFLINE). */
    @PutMapping("/{id}/status")
    public Courier status(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return service.setStatus(id, request.status());
    }
}
