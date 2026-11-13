package io.github.quant.server.backtest.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InFlightRequestGuardTest {
    @Test
    void closesAnOverdueExchangeAndKeepsThePermitUntilTheHandlerExits() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2);
             var deadlines = Executors.newSingleThreadScheduledExecutor();
             var client = HttpClient.newHttpClient()) {
            server.setExecutor(workers);
            var guard = new InFlightRequestGuard(1, exchange -> {
                if (exchange.getRequestURI().getRawPath().equals("/hold")) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                } else {
                    respond(exchange, 200, "ok");
                }
            }, deadlines, Duration.ofMillis(250));
            server.createContext("/", exchange -> {
                try {
                    guard.handle(exchange);
                } finally {
                    if (exchange.getRequestURI().getRawPath().equals("/hold")) exited.countDown();
                }
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            try {
                var pending = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/hold"))
                        .POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
                assertEquals(503, client.send(request(base, "/later"), HttpResponse.BodyHandlers.ofString()).statusCode());
            } finally {
                release.countDown();
            }
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(200, client.send(request(base, "/later"), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsSaturationAndReleasesPermitAfterCompletionOrFailure() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var activeCalls = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2);
             var client = HttpClient.newHttpClient()) {
            server.setExecutor(workers);
            var guard = new InFlightRequestGuard(1, exchange -> {
                activeCalls.incrementAndGet();
                switch (exchange.getRequestURI().getRawPath()) {
                    case "/hold" -> {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("hold fixture timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    }
                    case "/fail" -> throw new IOException("private-fixture-diagnostic");
                    default -> { }
                }
                respond(exchange, 200, "ok");
            });
            server.createContext("/", exchange -> {
                try (exchange) {
                    try {
                        guard.handle(exchange);
                    } catch (IOException failure) {
                        respond(exchange, 500, "generic failure");
                    } finally {
                        if ("/hold".equals(exchange.getRequestURI().getRawPath())) completed.countDown();
                    }
                }
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var pending = client.sendAsync(request(base, "/hold"), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var limited = client.send(request(base, "/later"), HttpResponse.BodyHandlers.ofString());
                assertEquals(503, limited.statusCode());
                assertEquals("{\"error\":\"request_capacity_exceeded\"}", limited.body());
                assertEquals("1", limited.headers().firstValue("Retry-After").orElseThrow());
                assertEquals("no-store", limited.headers().firstValue("Cache-Control").orElseThrow());
                assertEquals(1, activeCalls.get());
            } finally {
                release.countDown();
            }
            assertEquals(200, pending.get(5, TimeUnit.SECONDS).statusCode());
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            var failed = client.send(request(base, "/fail"), HttpResponse.BodyHandlers.ofString());
            assertEquals(500, failed.statusCode());
            assertEquals("generic failure", failed.body());
            assertEquals(200, client.send(request(base, "/later"), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(3, activeCalls.get());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static HttpRequest request(String base, String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer fixture-token-only").build();
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
