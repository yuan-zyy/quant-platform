package io.github.quant.market;

import io.github.quant.domain.Instrument;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** In-memory journal of original daily observations and their later publications. */
public final class VersionedMarketDataStore {
    private static final Comparator<DataFeed.Observation> PUBLICATION_ORDER = Comparator
            .comparing(DataFeed.Observation::availableTime)
            .thenComparing(observation -> observation instanceof DataFeed.Close)
            .thenComparing(DataFeed.Observation::eventTime)
            .thenComparing(observation -> observation.instrument().exchange())
            .thenComparing(observation -> observation.instrument().symbol())
            .thenComparing(DataFeed.Observation::date);

    private record Key(Instrument instrument, LocalDate date, boolean close) {
        static Key of(DataFeed.Observation observation) {
            return new Key(observation.instrument(), observation.date(), observation instanceof DataFeed.Close);
        }
    }

    private record Revision(long generation, DataFeed.Observation observation) {}

    private final String datasetVersion;
    private final Map<Key, NavigableMap<Instant, Revision>> history = new HashMap<>();
    private long generation;

    public VersionedMarketDataStore(String datasetVersion) {
        if (datasetVersion.isBlank()) throw new IllegalArgumentException("dataset version must be nonblank");
        this.datasetVersion = datasetVersion;
    }

    public String datasetVersion() { return datasetVersion; }

    /**
     * Appends one atomic batch. A previously published observation is idempotent when the
     * full payload matches, including decimal scale. Open and close keys can be imported
     * independently. Each observation key has one stable event time across revisions.
     */
    public synchronized void appendAll(Collection<DataFeed.Observation> observations) {
        Map<Key, NavigableMap<Instant, Revision>> pending = new HashMap<>();
        for (var observation : List.copyOf(observations)) {
            Key key = Key.of(observation);
            var stored = history.get(key);
            var additions = pending.get(key);
            if (stored != null) validateEventTime(stored, observation);
            if (additions != null) validateEventTime(additions, observation);

            var existing = stored == null ? null : stored.get(observation.availableTime());
            if (existing == null && additions != null) existing = additions.get(observation.availableTime());
            if (existing != null) {
                if (!existing.observation().equals(observation)) {
                    throw new IllegalArgumentException("conflicting publication for " + key);
                }
                continue;
            }
            pending.computeIfAbsent(key, ignored -> new TreeMap<>())
                    .put(observation.availableTime(), new Revision(Math.incrementExact(generation), observation));
        }
        if (pending.isEmpty()) return;
        pending.forEach((key, revisions) -> history.computeIfAbsent(key, ignored -> new TreeMap<>())
                .putAll(revisions));
        generation++;
    }

    public synchronized MarketDataSnapshot snapshot(Instant asOf) {
        return snapshot(asOf, generation);
    }

    public synchronized MarketDataSnapshot snapshot(Instant asOf, long atGeneration) {
        Objects.requireNonNull(asOf);
        if (atGeneration < 0 || atGeneration > generation) {
            throw new IllegalArgumentException("unknown dataset generation");
        }
        var latest = new ArrayList<DataFeed.Observation>();
        var first = new ArrayList<DataFeed.Observation>();
        for (var revisions : history.values()) {
            DataFeed.Observation latestVisible = null;
            for (var revision : revisions.headMap(asOf, true).values()) {
                if (revision.generation() > atGeneration) continue;
                if (latestVisible == null) first.add(revision.observation());
                latestVisible = revision.observation();
            }
            if (latestVisible != null) latest.add(latestVisible);
        }
        latest.sort(PUBLICATION_ORDER);
        first.sort(PUBLICATION_ORDER);
        return new MarketDataSnapshot(datasetVersion, asOf, atGeneration, latest, first);
    }

    private static void validateEventTime(NavigableMap<Instant, Revision> revisions, DataFeed.Observation observed) {
        if (!revisions.firstEntry().getValue().observation().eventTime().equals(observed.eventTime())) {
            throw new IllegalArgumentException("event time changed for " + Key.of(observed));
        }
    }
}
