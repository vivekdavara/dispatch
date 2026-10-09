package io.github.vivekdavara.dispatch.sim;

import java.util.Arrays;

/**
 * Exact percentiles of a set of latency samples, in milliseconds. Exact, not estimated: the simulator keeps every
 * sample (tens of thousands of longs), so there's no need for a histogram's bucketing error.
 *
 * <p>Percentiles use the nearest-rank method: p95 is the smallest sample that at least 95% of samples are at or
 * below, so it is always a value that was actually observed. An empty set has {@code count == 0} and zeros
 * elsewhere.
 */
public record LatencySummary(int count, double p50Ms, double p95Ms, double p99Ms, double maxMs, double meanMs) {

    private static final double NANOS_PER_MS = 1_000_000.0;

    /** Summarises {@code nanos} (durations in nanoseconds). The array isn't modified. */
    public static LatencySummary ofNanos(long[] nanos) {
        if (nanos.length == 0) {
            return new LatencySummary(0, 0, 0, 0, 0, 0);
        }
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (long n : sorted) {
            sum += n;
        }
        return new LatencySummary(sorted.length,
                percentile(sorted, 50) / NANOS_PER_MS,
                percentile(sorted, 95) / NANOS_PER_MS,
                percentile(sorted, 99) / NANOS_PER_MS,
                sorted[sorted.length - 1] / NANOS_PER_MS,
                sum / sorted.length / NANOS_PER_MS);
    }

    /** Nearest-rank percentile of an ascending, non-empty array; {@code p} in (0, 100]. */
    static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            throw new IllegalArgumentException("no samples");
        }
        if (!(p > 0 && p <= 100)) {
            throw new IllegalArgumentException("percentile must be in (0, 100]: " + p);
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }

    /** One line for a report, e.g. {@code p50 12.3 ms, p95 45.6 ms, p99 78.9 ms, max 120.0 ms (n=10000)}. */
    public String describe() {
        return String.format("p50 %.1f ms, p95 %.1f ms, p99 %.1f ms, max %.1f ms, mean %.1f ms (n=%d)",
                p50Ms, p95Ms, p99Ms, maxMs, meanMs, count);
    }
}
