package io.github.vivekdavara.dispatch.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Fingerprint of an order-creation request, stored next to the idempotency key so a retry can be told apart
 * from a different request that reuses the key.
 *
 * <p>It hashes a canonical form of the fields that define the order (not the raw JSON), so whitespace, field
 * order and an explicit {@code "tier": "STANDARD"} versus an omitted tier don't change it.
 */
public final class RequestHash {

    private RequestHash() {
    }

    public static String of(CreateOrderRequest r) {
        String canonical = String.format(Locale.ROOT, "v1|%s|%s,%s|%s,%s|%s",
                r.zoneId(),
                coordinate(r.pickup().lat()), coordinate(r.pickup().lng()),
                coordinate(r.dropoff().lat()), coordinate(r.dropoff().lng()),
                r.effectiveTier());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** Doubles printed in a fixed form, so 42.35 and 42.350 hash the same. */
    private static String coordinate(double v) {
        return Double.toString(v == 0.0 ? 0.0 : v); // folds -0.0 into 0.0
    }
}
