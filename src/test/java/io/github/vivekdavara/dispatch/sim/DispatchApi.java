package io.github.vivekdavara.dispatch.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** The parts of Dispatch's public API the simulator uses, over the JDK's HTTP and WebSocket client. */
final class DispatchApi {

    /** What {@code POST /api/v1/orders} answered. */
    record OrderResponse(int status, String orderId, boolean replayed) {
    }

    private final HttpClient http;
    private final URI base;
    private final ObjectMapper json;

    DispatchApi(URI base, ObjectMapper json) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.base = base;
        this.json = json;
    }

    /** A blocking call for setup; anything but 2xx is an error. */
    JsonNode call(String method, String path, Object body) {
        try {
            HttpResponse<String> r = http.send(request(method, path, body).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException(method + " " + path + " -> " + r.statusCode() + ": " + r.body());
            }
            return r.body().isEmpty() ? json.nullNode() : json.readTree(r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A fire-and-forget call: completes with the status code (or exceptionally if the request failed). */
    CompletableFuture<Integer> callAsync(String method, String path, Object body) {
        return http.sendAsync(request(method, path, body).build(), HttpResponse.BodyHandlers.discarding())
                .thenApply(HttpResponse::statusCode);
    }

    CompletableFuture<OrderResponse> createOrder(String idempotencyKey, Object body) {
        HttpRequest req = request("POST", "/api/v1/orders", body).header("Idempotency-Key", idempotencyKey).build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            String id = null;
            if (r.statusCode() == 200 || r.statusCode() == 201) {
                try {
                    id = json.readTree(r.body()).path("id").asText(null);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            boolean replayed = r.headers().firstValue("Idempotent-Replayed").map(Boolean::parseBoolean).orElse(false);
            return new OrderResponse(r.statusCode(), id, replayed);
        });
    }

    CompletableFuture<WebSocket> connect(String courierId, WebSocket.Listener listener) {
        URI ws = URI.create(base.toString().replaceFirst("^http", "ws") + "/ws/couriers/" + courierId);
        return http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(ws, listener);
    }

    private HttpRequest.Builder request(String method, String path, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30));
        if (body == null) {
            return b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
