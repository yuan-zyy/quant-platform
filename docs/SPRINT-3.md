# Sprint 3 market data

The Java market module imports historical daily observations into a versioned dataset. Opening price and quantity are separate from the closing OHLCV bar. Both observations carry the time of the market event and the time at which that observation became available. The backtest engine assigns processing time during replay.

## Historical snapshots and replay

Point-in-time queries select the latest publication visible at the snapshot cutoff. Daily replay uses the first publication for each instrument, session and observation kind. A later correction can appear in a point-in-time query while the replay continues to model the information available when the strategy originally made its decision. Each content-changing import increments the store generation. Record the dataset version, cutoff and generation for backtest provenance. An earlier generation remains queryable after a later import backfills publications that predate the cutoff. Preserve the original batches and their import order when rebuilding a dataset in another process.

## Import and run

```java
var store = new VersionedMarketDataStore("daily-v1");
try (var source = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
    new MarketDataCsvImporter().importInto(source, store);
}
var snapshot = store.snapshot(Instant.parse("2026-01-07T00:00:00Z"));
var result = new BacktestEngine().run(snapshot.replayFeed(), startingCash, rules, costs, strategy);
```

CSV columns are `dataset_version,event_type,symbol,exchange,session_date,event_time,available_time,open,high,low,close,volume,opening_quantity`. Use ISO dates and UTC timestamps. `OPEN` rows provide `open` and `opening_quantity`. `CLOSE` rows provide `open`, `high`, `low`, `close`, and `volume`. Leave unused columns blank. The importer validates a complete batch before committing it to the store.

## ClickHouse storage design

Store immutable publications in a `MergeTree` table. Keep all versions of each observation so an `as_of` query can select the latest one at or before its cutoff. A background merge must preserve historical publications. The ingest boundary owns uniqueness and conflict validation for `(dataset_version, exchange, symbol, session_date, observation_kind, available_time)`.

```sql
CREATE TABLE market_observations_v1
(
    dataset_version String,
    import_generation Int64,
    exchange LowCardinality(String),
    symbol String,
    session_date Date32,
    observation_kind Enum8('open' = 1, 'close' = 2),
    event_time DateTime64(9, 'UTC'),
    available_time DateTime64(9, 'UTC'),
    open_price Decimal(38, 12),
    opening_quantity Nullable(Int64),
    high_price Nullable(Decimal(38, 12)),
    low_price Nullable(Decimal(38, 12)),
    close_price Nullable(Decimal(38, 12)),
    session_volume Nullable(Int64),
    CONSTRAINT available_after_event CHECK available_time >= event_time
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(session_date)
ORDER BY (dataset_version, exchange, symbol, session_date, observation_kind, available_time);
```

For a snapshot, filter on dataset version, instrument/date range, `available_time <= as_of` and `import_generation <= pinned_generation`, then group by instrument, date and observation kind and use `argMax(tuple(...), available_time)` to select the payload and timestamps together. Use `argMin` to select first publications for replay. Enforce distinct availability timestamps for different payloads of the same key before loading, so the aggregates have a single winner. A future adapter should expose only fully committed import generations. Reject prices that cannot be represented exactly by the selected decimal scale and validate dates against the supported `DateTime64(9)` range before loading.

This DDL is a storage design for a future ClickHouse adapter. The current executable importer and snapshot store use process memory. ClickHouse deployment, persistent batch manifests, and factor data remain future work. [MergeTree reference](https://clickhouse.com/docs/reference/engines/table-engines/mergetree-family/mergetree) · [DateTime64 reference](https://clickhouse.com/docs/reference/data-types/datetime64)
