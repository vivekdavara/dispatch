package io.github.vivekdavara.dispatch.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vivekdavara.dispatch.domain.OrderTier;
import io.github.vivekdavara.dispatch.order.CreateOrderRequest.Location;
import org.junit.jupiter.api.Test;

class RequestHashTest {

    static CreateOrderRequest request(String zone, double pickupLat, OrderTier tier) {
        return new CreateOrderRequest(zone, new Location(pickupLat, -71.08), new Location(42.36, -71.06), tier);
    }

    @Test
    void sameRequestSameHash() {
        assertThat(RequestHash.of(request("z", 42.35, OrderTier.PRIORITY)))
                .isEqualTo(RequestHash.of(request("z", 42.35, OrderTier.PRIORITY)));
    }

    @Test
    void omittedTierHashesLikeStandard() {
        assertThat(RequestHash.of(request("z", 42.35, null)))
                .isEqualTo(RequestHash.of(request("z", 42.35, OrderTier.STANDARD)));
    }

    @Test
    void anyFieldChangeChangesTheHash() {
        String base = RequestHash.of(request("z", 42.35, OrderTier.STANDARD));
        assertThat(RequestHash.of(request("other", 42.35, OrderTier.STANDARD))).isNotEqualTo(base);
        assertThat(RequestHash.of(request("z", 42.3501, OrderTier.STANDARD))).isNotEqualTo(base);
        assertThat(RequestHash.of(request("z", 42.35, OrderTier.PRIORITY))).isNotEqualTo(base);
    }

    @Test
    void negativeZeroHashesLikeZero() {
        assertThat(RequestHash.of(request("z", -0.0, null))).isEqualTo(RequestHash.of(request("z", 0.0, null)));
    }

    @Test
    void isHexSha256() {
        assertThat(RequestHash.of(request("z", 42.35, null))).hasSize(64).matches("[0-9a-f]+");
    }
}
