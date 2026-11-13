package io.github.quant.event;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EventLoopTest {
    @Test void dispatchesByAvailabilityPriorityAndSequence() {
        var time = Instant.parse("2026-01-01T00:00:00Z");
        var loop = new EventLoop<String>();
        loop.publish(new TimedEvent<>(time,time,2,1,"later"));
        loop.publish(new TimedEvent<>(time,time,1,2,"first"));
        var result = new ArrayList<String>();
        loop.drain(e -> result.add(e.payload()));
        assertEquals(java.util.List.of("first","later"),result);
    }
}
