package io.github.quant.market.clickhouse;

import io.github.quant.market.DataFeed;
import io.github.quant.market.MarketDataSnapshot;
import io.github.quant.market.VersionedMarketDataStore;
import io.github.quant.market.ingest.MarketDataCsvImporter;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * A persistent batch journal for a bounded market dataset on one ClickHouse server.
 * Cooperating writers must use the same stable lock file on a filesystem with process file locking.
 */
public final class ClickHouseMarketDataStore {
    private final String datasetVersion;
    private final ClickHouseBatchJournal<VersionedMarketDataStore> journal;

    public ClickHouseMarketDataStore(URI endpoint, String datasetVersion, Path writerLock) {
        this(endpoint, datasetVersion, writerLock, Map.of());
    }

    public ClickHouseMarketDataStore(URI endpoint, String datasetVersion, Path writerLock,
                                     Map<String, String> headers) {
        this.datasetVersion = datasetVersion;
        journal = new ClickHouseBatchJournal<>("market_import_batches_v1", endpoint, datasetVersion, writerLock,
                headers, () -> new VersionedMarketDataStore(datasetVersion), VersionedMarketDataStore::generation,
                (store, payload) -> store.appendAll(MarketBatchCodec.decode(payload)));
    }

    /** Returns input row count, including publications already present. The reader stays open. */
    public int importCsv(Reader source) throws IOException, InterruptedException {
        var observations = new MarketDataCsvImporter().read(source, datasetVersion);
        appendAll(observations);
        return observations.size();
    }

    /** Returns the committed journal generation. An identical retry does not advance it. */
    public long appendAll(Collection<DataFeed.Observation> observations) throws IOException, InterruptedException {
        return journal.append(MarketBatchCodec.encode(observations));
    }

    public MarketDataSnapshot snapshot(Instant asOf) throws IOException, InterruptedException {
        return journal.read().snapshot(asOf);
    }

    public MarketDataSnapshot snapshot(Instant asOf, long generation) throws IOException, InterruptedException {
        return journal.read().snapshot(asOf, generation);
    }
}
