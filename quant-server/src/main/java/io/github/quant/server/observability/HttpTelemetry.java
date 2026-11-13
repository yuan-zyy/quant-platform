package io.github.quant.server.observability;

import com.sun.net.httpserver.HttpHandler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Process-local HTTP metrics with a fixed set of counters. */
public final class HttpTelemetry {
    public record Snapshot(long requests, long inFlight, long maxLatencyNanos, long informational,
                           long successful, long redirection, long clientErrors, long serverErrors,
                           long unanswered) {}

    private final LongAdder requests = new LongAdder();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong maxLatencyNanos = new AtomicLong();
    private final LongAdder[] responses = {
        new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()
    };

    public HttpHandler instrument(HttpHandler delegate) {
        Objects.requireNonNull(delegate);
        return exchange -> {
            long started = System.nanoTime();
            inFlight.incrementAndGet();
            requests.increment();
            try {
                delegate.handle(exchange);
            } finally {
                int statusClass = exchange.getResponseCode() / 100;
                responses[statusClass >= 1 && statusClass <= 5 ? statusClass : 0].increment();
                maxLatencyNanos.accumulateAndGet(System.nanoTime() - started, Math::max);
                inFlight.decrementAndGet();
            }
        };
    }

    public Snapshot snapshot() {
        return new Snapshot(requests.sum(), inFlight.get(), maxLatencyNanos.get(), responses[1].sum(),
                responses[2].sum(), responses[3].sum(), responses[4].sum(), responses[5].sum(), responses[0].sum());
    }
}
