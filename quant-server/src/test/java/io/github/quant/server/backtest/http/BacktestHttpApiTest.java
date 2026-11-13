package io.github.quant.server.backtest.http;

import com.sun.net.httpserver.HttpServer;
import io.github.quant.server.backtest.BacktestTaskScheduler;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BacktestHttpApiTest {
    @Test
    void checksIdentityRouteAndBodyBeforeResolvingJobs() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var scheduler = new BacktestTaskScheduler(1, 1, 1);
             var client = HttpClient.newHttpClient()) {
            var api = new BacktestHttpApi(scheduler, exchange ->
                    "Bearer known".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                            ? Optional.of("tenant-a") : Optional.empty(),
                    (tenant, scenario) -> {
                        calls.incrementAndGet();
                        assertEquals("tenant-a", tenant);
                        return "fixture".equals(scenario) ? Optional.of(() -> {
                            throw new IllegalStateException("private diagnostic");
                        }) : Optional.empty();
                    });
            server.createContext(BacktestHttpApi.PATH, api);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + BacktestHttpApi.PATH;

            assertEquals(401, send(client, base, "POST", "fixture", "text/plain", false).statusCode());
            assertEquals(404, send(client, base + "x", "POST", "fixture", "text/plain", true).statusCode());
            assertEquals(404, send(client, base + "/1-1-1-1-1", "GET", "", null, true).statusCode());
            assertEquals(404, send(client, base + "/" + UUID.randomUUID(), "DELETE", "", null, true).statusCode());
            var wrongMethod = send(client, base, "GET", "", null, true);
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElseThrow());
            assertEquals(415, send(client, base, "POST", "fixture", "application/json", true).statusCode());
            assertEquals(413, send(client, base, "POST", "x".repeat(129), "text/plain", true).statusCode());
            assertEquals(400, send(client, base, "POST", "../fixture", "text/plain", true).statusCode());
            assertEquals(0, calls.get());

            var unknown = send(client, base, "POST", "unknown", "text/plain; charset=UTF-8", true);
            assertEquals(404, unknown.statusCode());
            assertEquals("{\"error\":\"not_found\"}", unknown.body());
            scheduler.close();
            var unavailable = send(client, base, "POST", "fixture", "text/plain", true);
            assertEquals(503, unavailable.statusCode());
            assertEquals("{\"error\":\"scheduler_unavailable\"}", unavailable.body());
            assertEquals("no-store", unavailable.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    private static HttpResponse<String> send(HttpClient client, String uri, String method, String body,
                                             String contentType, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) request.header("Authorization", "Bearer known");
        if (contentType != null) request.header("Content-Type", contentType);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
