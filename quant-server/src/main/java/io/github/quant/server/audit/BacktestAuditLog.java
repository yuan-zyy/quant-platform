package io.github.quant.server.audit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Process-local audit history containing only fixed classifications and timestamps. */
public final class BacktestAuditLog {
    public enum Operation { SUBMIT, LOOKUP, CANCEL, OTHER }
    public enum Outcome { SUCCESS, CLIENT_ERROR, SERVER_ERROR, UNANSWERED, OTHER }
    public record Event(Instant time, Operation operation, Outcome outcome) {}

    private static final String PATH = "/v1/backtests";
    private static final Pattern TASK_PATH = Pattern.compile(PATH + "/[0-9a-fA-F]{8}"
            + "(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final Clock clock;
    private final int capacity;

    public BacktestAuditLog(int capacity) {
        this(capacity, Clock.systemUTC());
    }

    public BacktestAuditLog(int capacity, Clock clock) {
        if (capacity < 1 || capacity > 10_000) throw new IllegalArgumentException("audit capacity must be 1 to 10000");
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock);
    }

    public HttpHandler instrument(HttpHandler delegate) {
        Objects.requireNonNull(delegate);
        return exchange -> {
            Operation operation = operation(exchange);
            try {
                delegate.handle(exchange);
            } finally {
                append(new Event(clock.instant(), operation, outcome(exchange.getResponseCode())));
            }
        };
    }

    public synchronized List<Event> snapshot() {
        return List.copyOf(events);
    }

    private synchronized void append(Event event) {
        if (events.size() == capacity) events.removeFirst();
        events.addLast(event);
    }

    private static Operation operation(HttpExchange exchange) {
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        if (PATH.equals(path) && "POST".equals(method)) return Operation.SUBMIT;
        if (TASK_PATH.matcher(path).matches()) {
            if ("GET".equals(method)) return Operation.LOOKUP;
            if ("DELETE".equals(method)) return Operation.CANCEL;
        }
        return Operation.OTHER;
    }

    private static Outcome outcome(int status) {
        if (status < 0) return Outcome.UNANSWERED;
        return switch (status / 100) {
            case 2 -> Outcome.SUCCESS;
            case 4 -> Outcome.CLIENT_ERROR;
            case 5 -> Outcome.SERVER_ERROR;
            default -> Outcome.OTHER;
        };
    }
}
