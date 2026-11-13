package io.github.quant.event;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/** Deterministic, single-threaded event dispatcher. */
public final class EventLoop<T> {
    private final PriorityQueue<TimedEvent<T>> queue = new PriorityQueue<>(
            Comparator.<TimedEvent<T>, java.time.Instant>comparing(TimedEvent::availableTime)
                    .thenComparingInt(TimedEvent::priority).thenComparingLong(TimedEvent::sequence));
    public void publish(TimedEvent<T> event) { queue.add(event); }
    public int size() { return queue.size(); }
    public void drain(Consumer<TimedEvent<T>> handler) {
        while (!queue.isEmpty()) handler.accept(queue.remove());
    }
}
