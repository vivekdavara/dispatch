package io.github.vivekdavara.dispatch.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class LatencySummaryTest {

    static final long MS = 1_000_000L;

    @Test
    void oneToHundredMillisecondsHasTheTextbookPercentiles() {
        long[] samples = LongStream.rangeClosed(1, 100).map(ms -> ms * MS).toArray();

        LatencySummary s = LatencySummary.ofNanos(samples);

        assertThat(s.count()).isEqualTo(100);
        assertThat(s.p50Ms()).isEqualTo(50.0);
        assertThat(s.p95Ms()).isEqualTo(95.0);
        assertThat(s.p99Ms()).isEqualTo(99.0);
        assertThat(s.maxMs()).isEqualTo(100.0);
        assertThat(s.meanMs()).isEqualTo(50.5);
    }

    @Test
    void percentilesAreAlwaysObservedValues() {
        // Nearest rank on 10 samples: p95 is the 10th (ceil(9.5)), p50 the 5th. No interpolation.
        long[] samples = {10 * MS, 20 * MS, 30 * MS, 40 * MS, 50 * MS, 60 * MS, 70 * MS, 80 * MS, 90 * MS, 1000 * MS};

        LatencySummary s = LatencySummary.ofNanos(samples);

        assertThat(s.p50Ms()).isEqualTo(50.0);
        assertThat(s.p95Ms()).isEqualTo(1000.0);
    }

    @Test
    void inputOrderDoesNotMatterAndTheInputIsLeftAlone() {
        long[] samples = {5 * MS, 1 * MS, 4 * MS, 2 * MS, 3 * MS};

        LatencySummary s = LatencySummary.ofNanos(samples);

        assertThat(s.p50Ms()).isEqualTo(3.0);
        assertThat(s.maxMs()).isEqualTo(5.0);
        assertThat(samples).containsExactly(5 * MS, 1 * MS, 4 * MS, 2 * MS, 3 * MS);
    }

    @Test
    void oneSampleIsEveryPercentile() {
        LatencySummary s = LatencySummary.ofNanos(new long[] {7_500_000L});

        assertThat(s.p50Ms()).isEqualTo(7.5);
        assertThat(s.p99Ms()).isEqualTo(7.5);
        assertThat(s.maxMs()).isEqualTo(7.5);
    }

    @Test
    void noSamplesIsAnEmptySummary() {
        LatencySummary s = LatencySummary.ofNanos(new long[0]);

        assertThat(s.count()).isZero();
        assertThat(s.p95Ms()).isZero();
    }

    @Test
    void percentileOutsideItsRangeIsRejected() {
        long[] sorted = {1, 2, 3};

        assertThatThrownBy(() -> LatencySummary.percentile(sorted, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LatencySummary.percentile(sorted, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LatencySummary.percentile(sorted, 100)).isEqualTo(3);
        assertThat(LatencySummary.percentile(sorted, 0.1)).isEqualTo(1);
    }

    @Test
    void describeIsOneReadableLine() {
        LatencySummary s = LatencySummary.ofNanos(new long[] {MS, 2 * MS});

        assertThat(s.describe()).isEqualTo("p50 1.0 ms, p95 2.0 ms, p99 2.0 ms, max 2.0 ms, mean 1.5 ms (n=2)");
    }
}
