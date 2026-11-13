package io.github.quant.server.audit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static io.github.quant.server.audit.BacktestAuditLog.Operation.*;
import static io.github.quant.server.audit.BacktestAuditLog.Outcome.CLIENT_ERROR;
import static io.github.quant.server.audit.BacktestAuditLog.Outcome.SERVER_ERROR;
import static io.github.quant.server.audit.BacktestAuditLog.Outcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;

class BacktestAuditLogTest {
    private static final String TASK = "00000000-0000-0000-0000-000000000001";
    private static final Instant NOW = Instant.parse("2026-01-05T00:00:00Z");

    @Test
    void recordsFixedOperationsAndOutcomesWithBoundedRedactedRetention() throws Exception {
        var audit = new BacktestAuditLog(3, Clock.fixed(NOW, ZoneOffset.UTC));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var client = HttpClient.newHttpClient()) {
            var handler = audit.instrument(exchange -> {
                int status = switch (exchange.getRequestMethod()) {
                    case "POST" -> 202;
                    case "GET" -> 404;
                    case "DELETE" -> 503;
                    default -> 400;
                };
                exchange.sendResponseHeaders(status, -1);
            });
            server.createContext("/", exchange -> {
                try (exchange) {
                    handler.handle(exchange);
                }
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(202, client.send(request(base, "POST", "/v1/backtests?private-query=1"),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(404, client.send(request(base, "GET", "/v1/backtests/" + TASK),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(503, client.send(request(base, "DELETE", "/v1/backtests/" + TASK),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            var events = awaitSize(audit, 3);
            assertEquals(List.of(new BacktestAuditLog.Event(NOW, SUBMIT, SUCCESS),
                    new BacktestAuditLog.Event(NOW, LOOKUP, CLIENT_ERROR),
                    new BacktestAuditLog.Event(NOW, CANCEL, SERVER_ERROR)), events);
            assertDoesNotLeak(events);

            assertEquals(404, client.send(request(base, "GET", "/v1/backtests/private-path-segment"),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            List<BacktestAuditLog.Event> retained;
            do {
                retained = audit.snapshot();
                if (retained.getLast().operation() == OTHER) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertEquals(List.of(new BacktestAuditLog.Event(NOW, LOOKUP, CLIENT_ERROR),
                    new BacktestAuditLog.Event(NOW, CANCEL, SERVER_ERROR),
                    new BacktestAuditLog.Event(NOW, OTHER, CLIENT_ERROR)), retained);
            assertDoesNotLeak(retained);
            assertThrows(UnsupportedOperationException.class, retained::clear);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retainsAtMostCapacityDuringConcurrentRequests() throws Exception {
        var audit = new BacktestAuditLog(5);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var workers = Executors.newFixedThreadPool(4);
             var client = HttpClient.newHttpClient()) {
            server.setExecutor(workers);
            var handler = audit.instrument(exchange -> exchange.sendResponseHeaders(204, -1));
            server.createContext("/", exchange -> {
                try (exchange) {
                    handler.handle(exchange);
                }
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var responses = IntStream.range(0, 32)
                    .mapToObj(index -> client.sendAsync(request(base, "GET", "/v1/backtests/" + TASK + "?r=" + index),
                            HttpResponse.BodyHandlers.discarding()))
                    .toList();
            CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            for (var response : responses) assertEquals(204, response.join().statusCode());
            var events = awaitSize(audit, 5);
            assertEquals(5, events.size());
            assertTrue(events.stream().allMatch(event -> event.operation() == LOOKUP && event.outcome() == SUCCESS));
            assertDoesNotLeak(events);
        } finally {
            server.stop(0);
        }
    }

    private static HttpRequest request(String base, String method, String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer private-token-fixture")
                .header("X-Tenant-Id", "private-tenant-fixture")
                .method(method, HttpRequest.BodyPublishers.ofString("private-body-fixture")).build();
    }

    private static List<BacktestAuditLog.Event> awaitSize(BacktestAuditLog audit, int count)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<BacktestAuditLog.Event> events;
        do {
            events = audit.snapshot();
            if (events.size() == count) return events;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return events;
    }

    private static void assertDoesNotLeak(List<BacktestAuditLog.Event> events) {
        String representation = events.toString();
        assertFalse(representation.contains("private-"));
        assertFalse(representation.contains(TASK));
    }
}
