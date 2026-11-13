package io.github.quant.server.health;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HTTP health endpoints with one readiness check in flight and no queued probes. */
public final class HealthHttpHandler implements HttpHandler, AutoCloseable {
    public static final String LIVENESS_PATH = "/health/live";
    public static final String READINESS_PATH = "/health/ready";

    @FunctionalInterface
    public interface ReadinessProbe {
        boolean ready() throws Exception;
    }

    private final long timeoutMillis;
    private final ReadinessProbe probe;
    private final ThreadPoolExecutor executor;

    public HealthHttpHandler(Duration timeout, ReadinessProbe probe) {
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("readiness timeout must be between 1 ms and 30 seconds");
        }
        timeoutMillis = timeout.toMillis();
        this.probe = Objects.requireNonNull(probe);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().name("readiness-probe").daemon().factory());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            if (!LIVENESS_PATH.equals(path) && !READINESS_PATH.equals(path)) {
                respond(exchange, 404, "not_found");
            } else if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(exchange, 405, "method_not_allowed");
            } else if (LIVENESS_PATH.equals(path)) {
                respond(exchange, 200, "live");
            } else if (ready()) {
                respond(exchange, 200, "ready");
            } else {
                respond(exchange, 503, "not_ready");
            }
        }
    }

    private boolean ready() {
        Future<Boolean> result;
        try {
            result = executor.submit(probe::ready);
        } catch (RejectedExecutionException unavailable) {
            return false;
        }
        try {
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | CancellationException unavailable) {
            return false;
        } finally {
            result.cancel(true);
        }
    }

    private static void respond(HttpExchange exchange, int code, String status) throws IOException {
        byte[] bytes = ("{\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(code, -1);
        } else {
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
