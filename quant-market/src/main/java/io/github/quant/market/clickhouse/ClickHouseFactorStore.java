package io.github.quant.market.clickhouse;

import io.github.quant.market.factor.FactorSnapshotReader;
import io.github.quant.market.factor.FactorStore;
import io.github.quant.market.factor.FactorStore.Definition;
import io.github.quant.market.factor.FactorStore.FactorVersion;
import io.github.quant.market.factor.FactorStore.Snapshot;
import io.github.quant.market.factor.FactorStore.Value;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** Persistent factor journal for one bounded dataset on a ClickHouse server. */
public final class ClickHouseFactorStore {
    private final ClickHouseBatchJournal<FactorStore> journal;

    public ClickHouseFactorStore(URI endpoint, String datasetVersion, Path writerLock) {
        this(endpoint, datasetVersion, writerLock, Map.of());
    }

    public ClickHouseFactorStore(URI endpoint, String datasetVersion, Path writerLock, Map<String, String> headers) {
        journal = new ClickHouseBatchJournal<>("factor_import_batches_v1", endpoint, datasetVersion, writerLock,
                headers, FactorStore::new, FactorStore::generation, FactorBatchCodec::apply);
    }

    public long register(Definition definition) throws IOException, InterruptedException {
        return journal.append(FactorBatchCodec.encode(definition));
    }

    public long appendAll(FactorVersion factor, Collection<Value> values) throws IOException, InterruptedException {
        return journal.append(FactorBatchCodec.encode(factor, values));
    }

    public long generation() throws IOException, InterruptedException {
        return journal.read().generation();
    }

    public Snapshot snapshot(FactorVersion factor, Instant asOf) throws IOException, InterruptedException {
        return journal.read().snapshot(factor, asOf);
    }

    public Snapshot snapshot(FactorVersion factor, Instant asOf, long generation)
            throws IOException, InterruptedException {
        return journal.read().snapshot(factor, asOf, generation);
    }

    /** Loads one detached journal image for repeatable, multi-factor query evaluation. */
    public FactorSnapshotReader readView() throws IOException, InterruptedException {
        var store = journal.read();
        return new FactorSnapshotReader() {
            @Override public long generation() { return store.generation(); }
            @Override public Snapshot snapshot(FactorVersion factor, Instant asOf, long generation) {
                return store.snapshot(factor, asOf, generation);
            }
        };
    }
}
