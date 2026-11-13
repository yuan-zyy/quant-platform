package io.github.quant.server.health;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HealthHttpHandlerTest {
    @Test
    void livenessStaysAvailableAndReadinessRecoversAfterDependencyFailure() throws Exception {
        var calls = new AtomicInteger();
        try (var handler = new HealthHttpHandler(Duration.ofSeconds(2), () -> switch (calls.incrementAndGet()) {
            case 1 -> throw new IOException("private dependency fixture");
            case 2 -> false;
            default -> true;
        });
             var client = HttpClient.newHttpClient()) {
            var server = start(handler);
            try {
                assertStatus(200, "live", send(client, server, "GET", "/health/live"));
                assertEquals(0, calls.get());
                var method = send(client, server, "POST", "/health/ready");
                assertEquals(405, method.statusCode());
                assertEquals("GET", method.headers().firstValue("Allow").orElseThrow());
                assertEquals(404, send(client, server, "GET", "/health/ready/extra").statusCode());
                assertStatus(503, "not_ready", send(client, server, "GET", "/health/ready"));
                assertStatus(200, "live", send(client, server, "GET", "/health/live"));
                assertStatus(503, "not_ready", send(client, server, "GET", "/health/ready"));
                assertStatus(200, "ready", awaitReady(client, server));
                assertEquals(3, calls.get());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void timeoutInterruptsProbeAndLaterReadinessCanSucceed() throws Exception {
        var calls = new AtomicInteger();
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var handler = new HealthHttpHandler(Duration.ofMillis(250), () -> {
            if (calls.incrementAndGet() != 1) return true;
            try {
                return release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                interrupted.countDown();
                throw failure;
            }
        });
             var client = HttpClient.newHttpClient()) {
            var server = start(handler);
            try {
                assertStatus(503, "not_ready", send(client, server, "GET", "/health/ready"));
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                assertStatus(200, "live", send(client, server, "GET", "/health/live"));
                assertStatus(200, "ready", awaitReady(client, server));
                assertEquals(2, calls.get());
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }

    private static HttpServer start(HealthHttpHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", handler);
        server.start();
        return server;
    }

    private static HttpResponse<String> send(HttpClient client, HttpServer server, String method, String path)
            throws IOException, InterruptedException {
        var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer fixture-token-only")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> awaitReady(HttpClient client, HttpServer server)
            throws IOException, InterruptedException {
        // The previous probe can still be unwinding after the client receives its response.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        HttpResponse<String> result;
        do {
            result = send(client, server, "GET", "/health/ready");
            if (result.statusCode() != 503) return result;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return result;
    }

    private static void assertStatus(int status, String state, HttpResponse<String> response) {
        assertEquals(status, response.statusCode());
        assertEquals("{\"status\":\"" + state + "\"}", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
}
