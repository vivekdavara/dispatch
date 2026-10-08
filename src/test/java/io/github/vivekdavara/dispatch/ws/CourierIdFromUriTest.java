package io.github.vivekdavara.dispatch.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CourierIdFromUriTest {

    @Test
    void readsTheLastPathSegment() {
        UUID id = UUID.randomUUID();
        assertThat(CourierSocketHandler.courierIdFrom(URI.create("ws://h:1/ws/couriers/" + id))).contains(id);
        assertThat(CourierSocketHandler.courierIdFrom(URI.create("ws://h:1/ws/couriers/" + id + "?v=2")))
                .contains(id);
    }

    @Test
    void anythingElseIsEmpty() {
        assertThat(CourierSocketHandler.courierIdFrom(URI.create("ws://h:1/ws/couriers/not-a-uuid"))).isEmpty();
        assertThat(CourierSocketHandler.courierIdFrom(URI.create("ws://h:1/ws/couriers/"))).isEmpty();
        assertThat(CourierSocketHandler.courierIdFrom(null)).isEmpty();
    }
}
