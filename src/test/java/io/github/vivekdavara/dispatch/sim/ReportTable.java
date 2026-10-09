package io.github.vivekdavara.dispatch.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prints simulator reports as the Markdown table in the README, so the table is generated, not retyped:
 * {@code scripts/sim-table.sh target/sim/standard-before.json target/sim/standard-after.json ...}.
 */
public final class ReportTable {

    static final String HEADER = """
            | Run | First offer p50 | p95 | p99 | max | Server-side p50 | Freed → next offer p50 | Mean pass | \
            Orders examined per pass | Passes | Priority inversions |
            |---|---|---|---|---|---|---|---|---|---|---|""";

    private ReportTable() {
    }

    public static void main(String[] args) throws IOException {
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> reports = new ArrayList<>();
        for (String path : args) {
            reports.add(json.readTree(Path.of(path).toFile()));
        }
        System.out.println(render(reports));
    }

    static String render(List<JsonNode> reports) {
        StringBuilder out = new StringBuilder(HEADER);
        for (JsonNode r : reports) {
            JsonNode first = r.path("firstOffer");
            JsonNode inversions = r.path("orders").path("priorityInversions");
            out.append('\n').append(String.join(" | ", List.of(
                    "| " + r.path("label").asText(),
                    duration(first.path("p50Ms").asDouble()),
                    duration(first.path("p95Ms").asDouble()),
                    duration(first.path("p99Ms").asDouble()),
                    duration(first.path("maxMs").asDouble()),
                    r.path("firstOfferServer").isObject()
                            ? duration(r.path("firstOfferServer").path("p50Ms").asDouble()) : "n/a",
                    r.path("freedToNextOffer").isObject()
                            ? duration(r.path("freedToNextOffer").path("p50Ms").asDouble()) : "n/a",
                    String.format(Locale.ROOT, "%.1f ms", r.path("server").path("passMeanMs").asDouble()),
                    String.format(Locale.ROOT, "%.1f", r.path("server").path("ordersPerPass").asDouble()),
                    String.format(Locale.ROOT, "%,d", r.path("server").path("passes").asLong()),
                    inversions.isNumber() && inversions.asLong() >= 0
                            ? String.format(Locale.ROOT, "%,d", inversions.asLong()) : "n/a"))).append(" |");
        }
        return out.toString();
    }

    /** Milliseconds below a second, seconds above, one decimal. */
    static String duration(double ms) {
        return ms < 1000 ? String.format(Locale.ROOT, "%.1f ms", ms) : String.format(Locale.ROOT, "%.1f s", ms / 1000);
    }
}
