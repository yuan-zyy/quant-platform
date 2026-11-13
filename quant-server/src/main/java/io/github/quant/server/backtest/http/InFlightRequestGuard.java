package io.github.quant.server.backtest.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Bounds admitted HTTP exchanges and rejects excess requests without waiting for a permit. */
public final class InFlightRequestGuard implements HttpHandler {
    private static final byte[] SATURATED =
            "{\"error\":\"request_capacity_exceeded\"}".getBytes(StandardCharsets.UTF_8);

    private final Semaphore permits;
    private final HttpHandler delegate;
    private final ScheduledExecutorService deadlines;
    private final long timeoutNanos;

    public InFlightRequestGuard(int maxInFlight, HttpHandler delegate) {
        this(maxInFlight, delegate, null, null);
    }

    public InFlightRequestGuard(int maxInFlight, HttpHandler delegate,
                                ScheduledExecutorService deadlines, Duration timeout) {
        if (maxInFlight < 1) throw new IllegalArgumentException("max in-flight requests must be positive");
        if ((deadlines == null) != (timeout == null) || (timeout != null && timeout.toNanos() <= 0)) {
            throw new IllegalArgumentException("request timeout and scheduler must be supplied together");
        }
        permits = new Semaphore(maxInFlight);
        this.delegate = Objects.requireNonNull(delegate);
        this.deadlines = deadlines;
        timeoutNanos = timeout == null ? 0 : timeout.toNanos();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!permits.tryAcquire()) {
            try (exchange) {
                var headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/json; charset=utf-8");
                headers.set("Cache-Control", "no-store");
                headers.set("Retry-After", "1");
                exchange.sendResponseHeaders(503, SATURATED.length);
                exchange.getResponseBody().write(SATURATED);
            }
            return;
        }
        try {
            var deadline = deadlines == null ? null
                    : deadlines.schedule(exchange::close, timeoutNanos, TimeUnit.NANOSECONDS);
            try {
                delegate.handle(exchange);
            } finally {
                if (deadline != null) deadline.cancel(false);
            }
        } finally {
            permits.release();
        }
    }
}
