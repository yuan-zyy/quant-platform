package io.github.quant.event;

import java.time.Instant;
import java.util.Objects;

/** Monotonic simulated processing time, driven by data availability. */
public final class VirtualClock {
    private Instant now;

    public VirtualClock(Instant start) {
        now = Objects.requireNonNull(start);
    }

    public Instant now() {
        return now;
    }

    public void advanceTo(Instant time) {
        if (time.isBefore(now)) throw new IllegalArgumentException("processing time moved backwards");
        now = time;
    }
}
