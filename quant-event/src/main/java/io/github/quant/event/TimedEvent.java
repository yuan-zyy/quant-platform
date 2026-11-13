package io.github.quant.event;

import java.time.Instant;
import java.util.Objects;

/** Event time and data availability time are separate to avoid look-ahead bias. */
public record TimedEvent<T>(Instant eventTime, Instant availableTime, int priority, long sequence, T payload) {
    public TimedEvent {
        Objects.requireNonNull(eventTime);
        Objects.requireNonNull(availableTime);
        Objects.requireNonNull(payload);
        if (availableTime.isBefore(eventTime)) throw new IllegalArgumentException("availableTime before eventTime");
    }
}
