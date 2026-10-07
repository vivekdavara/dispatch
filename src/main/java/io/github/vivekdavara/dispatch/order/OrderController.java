package io.github.vivekdavara.dispatch.order;

import io.github.vivekdavara.dispatch.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/orders} with a required {@code Idempotency-Key} header: 201 for a new order, 200 with
 * {@code Idempotent-Replayed: true} for a retry, 409 when the key was used with a different body.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    /** Printable ASCII, 1 to 255 characters: a UUID, a ULID or a client's own request id all fit. */
    static final String KEY_PATTERN = "[\\x21-\\x7E]{1,255}";

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Order> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderService.Created c = service.create(idempotencyKey, request);
        URI location = URI.create("/api/v1/orders/" + c.order().id());
        return ResponseEntity.status(c.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(location)
                .header("Idempotent-Replayed", Boolean.toString(c.replayed()))
                .body(c.order());
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable UUID id) {
        return service.find(id).orElseThrow(() -> new ApiErrors.NotFoundException("order " + id));
    }
}
