package io.github.vivekdavara.dispatch.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportTableTest {

    final ObjectMapper json = new ObjectMapper();

    JsonNode report(String text) throws Exception {
        return json.readTree(text);
    }

    @Test
    void oneRowPerReportWithUnitsThatFitTheValue() throws Exception {
        JsonNode r = report("""
                {"label": "standard-after",
                 "firstOffer": {"p50Ms": 7.61, "p95Ms": 13585.8, "p99Ms": 16947.4, "maxMs": 20379.1},
                 "firstOfferServer": {"p50Ms": 3.94},
                 "freedToNextOffer": {"p50Ms": 426.8},
                 "server": {"passes": 21433, "passMeanMs": 2.0, "ordersPerPass": 0.5},
                 "orders": {"priorityInversions": 1234}}""");

        String table = ReportTable.render(List.of(r));

        assertThat(table.lines().toList()).hasSize(3);
        assertThat(table.lines().toList().get(2)).isEqualTo("| standard-after | 7.6 ms | 13.6 s | 16.9 s | 20.4 s | "
                + "3.9 ms | 426.8 ms | 2.0 ms | 0.5 | 21,433 | 1,234 |");
    }

    @Test
    void missingOptionalSectionsShowAsNotAvailable() throws Exception {
        JsonNode r = report("""
                {"label": "no-db",
                 "firstOffer": {"p50Ms": 1, "p95Ms": 2, "p99Ms": 3, "maxMs": 4},
                 "firstOfferServer": null,
                 "server": {"passes": 1, "passMeanMs": 1, "ordersPerPass": 1},
                 "orders": {}}""");

        assertThat(ReportTable.render(List.of(r))).endsWith("| 4.0 ms | n/a | n/a | 1.0 ms | 1.0 | 1 | n/a |");
    }

    @Test
    void durationsSwitchToSecondsAtOneSecond() {
        assertThat(ReportTable.duration(999.94)).isEqualTo("999.9 ms");
        assertThat(ReportTable.duration(1000)).isEqualTo("1.0 s");
        assertThat(ReportTable.duration(166_667.4)).isEqualTo("166.7 s");
    }
}
