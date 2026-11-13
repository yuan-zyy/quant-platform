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

## ClickHouse persistence

Apply [market_import_batches_v1.sql](../quant-market/src/main/resources/clickhouse/market_import_batches_v1.sql) to the configured ClickHouse database. For persistent import and replay, use the same CSV with a ClickHouse-backed store:

```java
var store = new ClickHouseMarketDataStore(clickHouseUri, "daily-v1", writerLockPath, requestHeaders);
try (var source = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
    store.importCsv(source);
}
var snapshot = store.snapshot(Instant.parse("2026-01-07T00:00:00Z"));
```

Each accepted content-changing import creates one immutable row holding its whole batch. Reads verify and rebuild the generation journal before selecting an as-of snapshot. The persistent adapter loads a dataset's batches into memory, so this path is intended for bounded historical datasets. The normalized observation table below is the design for larger analytical queries.

Assign one cooperating writer location per endpoint, database and dataset. All writers for that dataset must use the same lock file on a filesystem with reliable exclusive locks and preserve the adjacent pending-write journal. After an uncertain response, retry the original import through the same writer location. A visible matching batch resolves an uncertain response. If the batch remains invisible, only the same operation may be retried until the earlier write is resolved. Identical repeated journal rows are treated as one generation. Keep write credentials scoped to the worker and configure TLS for remote deployments. Distributed writer coordination, backup and deployment are separate work.

GitHub Actions starts a temporary ClickHouse instance on loopback and runs Java tests for CSV import, generation recovery after a simulated response loss, and backtest execution. The tests can also be run with `mvn -B -ntp -pl quant-backtest -am -Dquant.clickhouse.url=http://127.0.0.1:8123/ verify` against a test database after applying the batch DDL. Set `QUANT_CLICKHOUSE_USER` and `QUANT_CLICKHOUSE_PASSWORD` if required.

## ClickHouse analytical queries

Apply [market_observations_view_v1.sql](../quant-market/src/main/resources/clickhouse/market_observations_view_v1.sql) after the batch table. The view decodes immutable batches at read time, folds physically repeated batch rows and preserves exact prices and timestamps as text. This keeps the batch journal as the only write path. Native SQL callers must parse the time strings before comparing them, since their fractional precision varies.

Java callers can query the view through `ClickHouseMarketObservations.snapshot(datasetVersion, instrument, fromInclusive, toInclusive, asOf, generation)`. The adapter bounds one query to 10,000 projected rows for the selected dataset, instrument and generation. It filters dates and publication time using Java types and returns latest visible observations and a first-publication replay feed. Obtain the pinned generation from the authoritative store. The view scans and decodes batches and assumes a valid journal written through the persistent adapter.

## ClickHouse materialized table design

Store immutable publications in a `MergeTree` table. Keep all versions of each observation so an `as_of` query can select the latest one at or before its cutoff. A background merge must preserve historical publications. The ingest boundary owns uniqueness and conflict validation for `(dataset_version, exchange, symbol, session_date, observation_kind, available_time)`.

The executable DDL lives in [market_observations_v1.sql](../quant-market/src/main/resources/clickhouse/market_observations_v1.sql).

For a snapshot, filter on dataset version, instrument/date range, `available_time <= as_of` and `import_generation <= pinned_generation`, then group by instrument, date and observation kind and use `argMax(tuple(...), available_time)` to select the payload and timestamps together. Use `argMin` to select first publications for replay. Enforce distinct availability timestamps for different payloads of the same key before loading, so the aggregates have a single winner. A future adapter should expose only fully committed import generations. Reject prices that cannot be represented exactly by the selected decimal scale and validate dates against the supported `DateTime64(9)` range before loading.

GitHub Actions applies the table and view DDL and checks revision visibility, generation pinning and first-publication replay. Materialized storage for larger workloads remains planned. [MergeTree reference](https://clickhouse.com/docs/reference/engines/table-engines/mergetree-family/mergetree) · [DateTime64 reference](https://clickhouse.com/docs/reference/data-types/datetime64)

## Factor research data

`FactorStore` registers immutable definitions by factor key and version. Values carry both event and publication times and preserve exact decimals. A snapshot exposes only the requested version and publications available by the cutoff. Store generation pinning preserves old snapshots after backfilled revisions.

The typed factor planner evaluates explicit version references, decimal arithmetic and boolean predicates for one instrument at a time. Supply the bar's event time, the strategy's current processing time and a pinned factor-store generation when evaluating a plan in a backtest. A reference looks up the exact event time and selects the latest revision visible at processing time. Use the same generation for every callback in a reproducible run.

```java
var signal = new FactorQuery.Reference(new FactorStore.FactorVersion("signal", "1"));
var plan = FactorQueryPlanner.plan(new FactorQuery.Compare(FactorQuery.Comparison.GT,
        signal, new FactorQuery.Literal(BigDecimal.ZERO)));
long generation = factors.generation();
// In BacktestEngine.Strategy.onClose:
var buy = plan.evaluate(factors, close.instrument(), close.eventTime(), context.processingTime(), generation);
```

A visible factor version with no value at the requested event returns an empty result if that value is needed. An undefined or unavailable factor version is a query error. Conditional expressions evaluate only the selected value branch. Decimal addition, subtraction and multiplication are exact. Division uses `DECIMAL128` and raises an arithmetic error on a zero divisor. The evaluator accepts trusted Java expressions. Cross-sectional and rolling queries remain planned.

Apply [factor_import_batches_v1.sql](../quant-market/src/main/resources/clickhouse/factor_import_batches_v1.sql) to persist definitions and values through `ClickHouseFactorStore`. Both operation types share a dataset generation. As with market data, configure one cooperating writer location and preserve its lock and pending marker. Use a separate lock path for each factor dataset and a different path from the market journal. The adapter reloads and validates the immutable journal on a single ClickHouse server.

Call `readView()` before starting a backtest and pass that view to the planner. This reads ClickHouse once and exposes a fixed, read-only factor snapshot source for all callbacks. Keep the dataset ID and generation with the run record. The in-memory view is suitable for bounded factor datasets.
