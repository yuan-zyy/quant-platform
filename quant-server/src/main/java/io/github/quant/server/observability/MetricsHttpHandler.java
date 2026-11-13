package io.github.quant.server.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Exposes a fixed metrics set to a client connected over loopback. */
public final class MetricsHttpHandler implements HttpHandler {
    public static final String PATH = "/metrics";
    private final HttpTelemetry telemetry;

    public MetricsHttpHandler(HttpTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            var remote = exchange.getRemoteAddress().getAddress();
            if (remote == null || !remote.isLoopbackAddress() || !PATH.equals(exchange.getRequestURI().getRawPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                headers.set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            var snapshot = telemetry.snapshot();
            byte[] body = """
                    # TYPE quant_http_requests_total counter
                    quant_http_requests_total %d
                    # TYPE quant_http_in_flight gauge
                    quant_http_in_flight %d
                    # TYPE quant_http_max_duration_seconds gauge
                    quant_http_max_duration_seconds %s
                    # TYPE quant_http_responses_total counter
                    quant_http_responses_total{class="1xx"} %d
                    quant_http_responses_total{class="2xx"} %d
                    quant_http_responses_total{class="3xx"} %d
                    quant_http_responses_total{class="4xx"} %d
                    quant_http_responses_total{class="5xx"} %d
                    quant_http_responses_total{class="unanswered"} %d
                    """.formatted(snapshot.requests(), snapshot.inFlight(), snapshot.maxLatencyNanos() / 1e9,
                    snapshot.informational(), snapshot.successful(), snapshot.redirection(), snapshot.clientErrors(),
                    snapshot.serverErrors(), snapshot.unanswered()).getBytes(StandardCharsets.UTF_8);
            headers.set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
