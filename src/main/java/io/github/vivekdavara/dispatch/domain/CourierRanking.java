package io.github.vivekdavara.dispatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Picks the courier for an order: the nearest available one, where distances within {@link #TIE_METERS} of the
 * nearest count as a tie, broken by longest idle time and then by courier id.
 *
 * <p>Pure function: callers pass in already-filtered candidates (available, same zone, fresh location) and the
 * pickup point.
 */
public final class CourierRanking {

    /** Distances closer than this to the best one are a tie; GPS noise is larger than this. */
    public static final double TIE_METERS = 50.0;

    private CourierRanking() {
    }

    /** A courier the engine could offer the order to. */
    public record Candidate(UUID courierId, GeoPoint location, Instant idleSince) {
    }

    /** A candidate with its distance to the pickup. */
    public record Ranked(Candidate candidate, double distanceMeters) {
    }

    /**
     * Ranks candidates within {@code maxPickupMeters} of {@code pickup}, best first. The first entry is the
     * courier to offer the order to; the rest are fallbacks if that courier is claimed by someone else.
     */
    public static List<Ranked> rank(GeoPoint pickup, List<Candidate> candidates, double maxPickupMeters, Instant now) {
        List<Ranked> inRange = new ArrayList<>();
        for (Candidate c : candidates) {
            double d = c.location().distanceMetersTo(pickup);
            if (d <= maxPickupMeters) {
                inRange.add(new Ranked(c, d));
            }
        }
        inRange.sort(Comparator.comparingDouble(Ranked::distanceMeters));

        // Group into bands of near-equal distance: each band starts at the first courier not within TIE_METERS
        // of the band's nearest member. Within a band, longest idle wins, then smallest id.
        Comparator<Ranked> withinBand = Comparator
                .comparing((Ranked r) -> idleFor(r.candidate(), now)).reversed()
                .thenComparing(r -> r.candidate().courierId());
        List<Ranked> result = new ArrayList<>(inRange.size());
        int bandStart = 0;
        while (bandStart < inRange.size()) {
            double bandBase = inRange.get(bandStart).distanceMeters();
            int bandEnd = bandStart;
            while (bandEnd < inRange.size() && inRange.get(bandEnd).distanceMeters() - bandBase < TIE_METERS) {
                bandEnd++;
            }
            List<Ranked> band = new ArrayList<>(inRange.subList(bandStart, bandEnd));
            band.sort(withinBand);
            result.addAll(band);
            bandStart = bandEnd;
        }
        return result;
    }

    /** The single best courier, if any is in range. */
    public static Optional<Ranked> best(GeoPoint pickup, List<Candidate> candidates, double maxPickupMeters, Instant now) {
        List<Ranked> ranked = rank(pickup, candidates, maxPickupMeters, now);
        return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0));
    }

    private static Duration idleFor(Candidate c, Instant now) {
        Duration d = Duration.between(c.idleSince(), now);
        return d.isNegative() ? Duration.ZERO : d;
    }
}
