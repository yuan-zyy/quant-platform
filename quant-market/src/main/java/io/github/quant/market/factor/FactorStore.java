package io.github.quant.market.factor;

import io.github.quant.domain.Instrument;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** In-memory journal for immutable factor definitions and published value revisions. */
public final class FactorStore {
    public record FactorVersion(String name, String version) {
        public FactorVersion {
            if (name.isBlank() || version.isBlank()) {
                throw new IllegalArgumentException("factor name and version must be nonblank");
            }
        }
    }

    public record Definition(FactorVersion factor, String description, String unit, Instant availableTime) {
        public Definition {
            Objects.requireNonNull(factor);
            Objects.requireNonNull(description);
            if (unit.isBlank()) throw new IllegalArgumentException("factor unit must be nonblank");
            Objects.requireNonNull(availableTime);
        }
    }

    public record Value(Instrument instrument, Instant eventTime, Instant availableTime, BigDecimal value) {
        public Value {
            Objects.requireNonNull(instrument);
            Objects.requireNonNull(value);
            if (availableTime.isBefore(eventTime)) {
                throw new IllegalArgumentException("factor available time precedes event time");
            }
        }
    }

    public record Snapshot(Definition definition, Instant asOf, long generation, List<Value> values) {
        public Snapshot {
            values = List.copyOf(values);
        }
    }

    private record ObservationKey(Instrument instrument, Instant eventTime) {
        private static ObservationKey of(Value value) {
            return new ObservationKey(value.instrument(), value.eventTime());
        }
    }

    private record PublicationKey(ObservationKey observation, Instant availableTime) {
        private static PublicationKey of(Value value) {
            return new PublicationKey(ObservationKey.of(value), value.availableTime());
        }
    }

    private record Publication(long generation, Value value) {}

    private record History(Definition definition, long generation, Map<PublicationKey, Publication> publications) {}

    private static final Comparator<Value> VALUE_ORDER = Comparator.comparing(Value::eventTime)
            .thenComparing(value -> value.instrument().exchange())
            .thenComparing(value -> value.instrument().symbol());

    private final Map<FactorVersion, History> histories = new HashMap<>();
    private long generation;

    /** Captures a common journal position for queries that read multiple factor versions. */
    public synchronized long generation() { return generation; }

    /** Publishes a definition once. A changed definition requires a new version. */
    public synchronized void register(Definition definition) {
        var existing = histories.get(definition.factor());
        if (existing != null) {
            if (!existing.definition().equals(definition)) {
                throw new IllegalArgumentException("conflicting factor definition");
            }
            return;
        }
        long next = Math.incrementExact(generation);
        histories.put(definition.factor(), new History(definition, next, new HashMap<>()));
        generation = next;
    }

    /** Atomically adds one batch. Equal payloads including decimal scale are idempotent. */
    public synchronized void appendAll(FactorVersion factor, Collection<Value> values) {
        var history = histories.get(factor);
        if (history == null) throw new IllegalArgumentException("unknown factor version");
        var pending = new HashMap<PublicationKey, Value>();
        for (var value : List.copyOf(values)) {
            if (value.availableTime().isBefore(history.definition().availableTime())) {
                throw new IllegalArgumentException("factor value published before its definition");
            }
            var key = PublicationKey.of(value);
            var existing = history.publications().get(key);
            var previous = existing == null ? pending.putIfAbsent(key, value) : existing.value();
            if (previous != null && !previous.equals(value)) {
                throw new IllegalArgumentException("conflicting factor publication for " + key);
            }
        }
        if (pending.isEmpty()) return;
        long next = Math.incrementExact(generation);
        pending.forEach((key, value) -> history.publications().put(key, new Publication(next, value)));
        generation = next;
    }

    public synchronized Snapshot snapshot(FactorVersion factor, Instant asOf) {
        return snapshot(factor, asOf, generation);
    }

    /** Selects the latest visible publication per instrument and event at a pinned generation. */
    public synchronized Snapshot snapshot(FactorVersion factor, Instant asOf, long atGeneration) {
        Objects.requireNonNull(asOf);
        if (atGeneration < 0 || atGeneration > generation) {
            throw new IllegalArgumentException("unknown factor generation");
        }
        var history = histories.get(factor);
        if (history == null || history.generation() > atGeneration || history.definition().availableTime().isAfter(asOf)) {
            throw new IllegalArgumentException("factor definition is not visible");
        }
        var latest = new HashMap<ObservationKey, Value>();
        history.publications().forEach((key, publication) -> {
            var value = publication.value();
            if (publication.generation() <= atGeneration && !value.availableTime().isAfter(asOf)) {
                latest.merge(key.observation(), value,
                        (first, second) -> first.availableTime().isAfter(second.availableTime()) ? first : second);
            }
        });
        var ordered = new ArrayList<>(latest.values());
        ordered.sort(VALUE_ORDER);
        return new Snapshot(history.definition(), asOf, atGeneration, ordered);
    }
}
