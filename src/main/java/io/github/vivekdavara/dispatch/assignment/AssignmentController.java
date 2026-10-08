package io.github.vivekdavara.dispatch.assignment;

import io.github.vivekdavara.dispatch.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A courier's side of an assignment over HTTP. The WebSocket carries accept and decline too; these endpoints exist
 * for clients without a socket, for the rest of the delivery (pickup, delivery), and for tests. The courier names
 * itself in the body; there's no authentication yet (see README limitations), but an assignment answers only to
 * its own courier.
 */
@RestController
@RequestMapping("/api/v1/assignments")
public class AssignmentController {

    public record CourierRequest(@NotNull UUID courierId) {
    }

    private final OfferService offers;
    private final AssignmentRepository assignments;

    public AssignmentController(OfferService offers, AssignmentRepository assignments) {
        this.offers = offers;
        this.assignments = assignments;
    }

    @GetMapping("/{id}")
    public Assignment get(@PathVariable long id) {
        return assignments.find(id).orElseThrow(() -> new ApiErrors.NotFoundException("assignment " + id));
    }

    @PostMapping("/{id}/accept")
    public Assignment accept(@PathVariable long id, @Valid @RequestBody CourierRequest r) {
        return offers.accept(id, r.courierId());
    }

    @PostMapping("/{id}/decline")
    public Assignment decline(@PathVariable long id, @Valid @RequestBody CourierRequest r) {
        return offers.decline(id, r.courierId());
    }

    @PostMapping("/{id}/pickup")
    public Assignment pickup(@PathVariable long id, @Valid @RequestBody CourierRequest r) {
        return offers.pickedUp(id, r.courierId());
    }

    @PostMapping("/{id}/deliver")
    public Assignment deliver(@PathVariable long id, @Valid @RequestBody CourierRequest r) {
        return offers.delivered(id, r.courierId());
    }
}
