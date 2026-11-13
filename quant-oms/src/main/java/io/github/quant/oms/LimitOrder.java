package io.github.quant.oms;

import io.github.quant.domain.Instrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class LimitOrder {
    public enum Side { BUY, SELL }
    public enum Status { OPEN, PARTIALLY_FILLED, FILLED, REJECTED, CANCELED }

    public record Request(Instrument instrument, Side side, long quantity, BigDecimal limit) {
        public Request {
            Objects.requireNonNull(instrument);
            Objects.requireNonNull(side);
            if (quantity <= 0 || limit.signum() <= 0) {
                throw new IllegalArgumentException("quantity and limit must be positive");
            }
        }
    }

    public record Snapshot(long id, Request request, Instant submittedAt, long filledQuantity,
                           Status status, String reason) {}

    private final long id;
    private final Request request;
    private final Instant submittedAt;
    private long filledQuantity;
    private Status status = Status.OPEN;
    private String reason = "";

    public LimitOrder(long id, Request request, Instant submittedAt) {
        this.id = id;
        this.request = Objects.requireNonNull(request);
        this.submittedAt = Objects.requireNonNull(submittedAt);
    }

    public Request request() { return request; }
    public Instant submittedAt() { return submittedAt; }
    public long remaining() { return request.quantity() - filledQuantity; }
    public boolean active() { return status == Status.OPEN || status == Status.PARTIALLY_FILLED; }

    public void fill(long quantity) {
        requireActive();
        if (quantity <= 0 || quantity > remaining()) throw new IllegalArgumentException("invalid fill quantity");
        filledQuantity += quantity;
        status = remaining() == 0 ? Status.FILLED : Status.PARTIALLY_FILLED;
    }

    public void reject(String detail) {
        requireActive();
        status = filledQuantity == 0 ? Status.REJECTED : Status.CANCELED;
        reason = Objects.requireNonNull(detail);
    }

    public void cancel(String detail) {
        requireActive();
        status = Status.CANCELED;
        reason = Objects.requireNonNull(detail);
    }

    public Snapshot snapshot() {
        return new Snapshot(id, request, submittedAt, filledQuantity, status, reason);
    }

    private void requireActive() {
        if (!active()) throw new IllegalStateException("order is terminal");
    }
}
