package io.github.quant.server.observability;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpTelemetryTest {
    @Test
    void measuresConcurrentRequestsByStatusClassWithoutExportingRequestValues() throws Exception {
        var telemetry = new HttpTelemetry();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(4);
             var client = HttpClient.newHttpClient()) {
            server.setExecutor(threads);
            var wrapped = telemetry.instrument(exchange -> {
                String path = exchange.getRequestURI().getRawPath();
                if (path.endsWith("/hold")) {
                    entered.countDown();
                    try {
                        if (!released.await(5, TimeUnit.SECONDS)) throw new IOException("hold timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                }
                int status = path.endsWith("/missing") ? 404 : path.endsWith("/failure") ? 500 : 204;
                exchange.sendResponseHeaders(status, -1);
            });
            server.createContext("/private-fixture-tenant", exchange -> {
                try (exchange) {
                    wrapped.handle(exchange);
                }
            });
            server.createContext("/metrics", new MetricsHttpHandler(telemetry));
            server.start();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var held = send(client, endpoint, "/private-fixture-tenant/hold?token=fixture-query");
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, telemetry.snapshot().inFlight());
                var parallel = List.of(send(client, endpoint, "/private-fixture-tenant/missing"),
                        send(client, endpoint, "/private-fixture-tenant/failure"),
                        send(client, endpoint, "/private-fixture-tenant/success"));
                assertEquals(404, parallel.get(0).get(5, TimeUnit.SECONDS).statusCode());
                assertEquals(500, parallel.get(1).get(5, TimeUnit.SECONDS).statusCode());
                assertEquals(204, parallel.get(2).get(5, TimeUnit.SECONDS).statusCode());
            } finally {
                released.countDown();
            }
            assertEquals(204, held.get(5, TimeUnit.SECONDS).statusCode());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (telemetry.snapshot().inFlight() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            var snapshot = telemetry.snapshot();
            assertEquals(4, snapshot.requests());
            assertEquals(0, snapshot.inFlight());
            assertEquals(2, snapshot.successful());
            assertEquals(1, snapshot.clientErrors());
            assertEquals(1, snapshot.serverErrors());
            assertEquals(0, snapshot.unanswered());
            assertTrue(snapshot.maxLatencyNanos() > 0);

            var metrics = send(client, endpoint, "/metrics").get(5, TimeUnit.SECONDS);
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.headers().firstValue("Content-Type").orElseThrow().startsWith("text/plain"));
            var lines = metrics.body().lines().toList();
            assertTrue(lines.contains("quant_http_requests_total 4"));
            assertTrue(lines.contains("quant_http_in_flight 0"));
            assertTrue(lines.contains("quant_http_responses_total{class=\"2xx\"} 2"));
            assertTrue(lines.contains("quant_http_responses_total{class=\"4xx\"} 1"));
            assertTrue(lines.contains("quant_http_responses_total{class=\"5xx\"} 1"));
            assertFalse(metrics.body().contains("private-fixture-tenant"));
            assertFalse(metrics.body().contains("fixture-token-only"));
            assertFalse(metrics.body().contains("fixture-query"));
            assertEquals(4, telemetry.snapshot().requests());
        } finally {
            released.countDown();
            server.stop(0);
        }
    }

    private static CompletableFuture<HttpResponse<String>> send(HttpClient client, String base, String path) {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer fixture-token-only").build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
}
