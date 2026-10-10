package io.github.vivekdavara.dispatch.ws;

import io.github.vivekdavara.dispatch.courier.CourierRepository;
import io.github.vivekdavara.dispatch.web.ApiErrors;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * A courier's open offer over HTTP, for apps without a socket: the same {@code offer} message the socket pushes,
 * so an app can poll for work, then answer with {@code POST /api/v1/assignments/{id}/accept} or {@code /decline}.
 * 204 when the courier has no open offer.
 */
@RestController
public class OpenOfferController {

    private final CourierSocketHandler hub;
    private final CourierRepository couriers;

    public OpenOfferController(CourierSocketHandler hub, CourierRepository couriers) {
        this.hub = hub;
        this.couriers = couriers;
    }

    @GetMapping("/api/v1/couriers/{id}/offer")
    public ResponseEntity<CourierMessages.OfferMessage> openOffer(@PathVariable UUID id) {
        if (couriers.find(id).isEmpty()) {
            throw new ApiErrors.NotFoundException("courier " + id);
        }
        return hub.openOffer(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
