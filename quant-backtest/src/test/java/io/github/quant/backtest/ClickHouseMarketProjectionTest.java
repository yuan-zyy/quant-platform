package io.github.quant.backtest;

import io.github.quant.domain.Instrument;
import io.github.quant.market.DataFeed;
import io.github.quant.market.clickhouse.ClickHouseMarketDataStore;
import io.github.quant.market.clickhouse.ClickHouseMarketObservations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "quant.clickhouse.url", matches = "https?://.+")
class ClickHouseMarketProjectionTest {
    private static final String HEADER = "dataset_version,event_type,symbol,exchange,session_date,event_time,"
            + "available_time,open,high,low,close,volume,opening_quantity\n";
    @TempDir Path directory;

    @Test
    void matchesJournalSnapshotsForTheSelectedInstrumentAndTradingDate() throws Exception {
        var endpoint = URI.create(System.getProperty("quant.clickhouse.url"));
        String user = System.getenv("QUANT_CLICKHOUSE_USER");
        Map<String, String> headers = user == null ? Map.of() : Map.of("X-ClickHouse-User", user,
                "X-ClickHouse-Key", System.getenv("QUANT_CLICKHOUSE_PASSWORD"));
        String version = UUID.randomUUID().toString();
        var journal = new ClickHouseMarketDataStore(endpoint, version, directory.resolve("projection.lock"), headers);
        var open = "%s,OPEN,600000,SSE,2026-01-05,2026-01-05T01:30:00Z,2026-01-05T01:30:00Z,10.000,,,,,100\n"
                .formatted(version);
        journal.importCsv(new StringReader(HEADER + open + """
                %1$s,CLOSE,600000,SSE,2026-01-05,2026-01-05T07:00:00Z,2026-01-05T07:00:00Z,10.000,12,9,11,1000,
                %1$s,OPEN,600001,SSE,2026-01-05,2026-01-05T01:30:00Z,2026-01-05T01:30:00Z,10,,,,,100
                %1$s,OPEN,600000,SSE,2026-01-06,2026-01-06T01:30:00Z,2026-01-06T01:30:00Z,10,,,,,100
                """.formatted(version)));
        journal.importCsv(new StringReader(HEADER + open + """
                %s,CLOSE,600000,SSE,2026-01-05,2026-01-05T07:00:00Z,2026-01-05T07:00:00.000000001Z,10.000,12,9,12,1100,
                """.formatted(version)));

        var view = new ClickHouseMarketObservations(endpoint, headers);
        var instrument = new Instrument("600000", "SSE");
        var date = LocalDate.of(2026, 1, 5);
        for (var cutoff : new Instant[] {Instant.parse("2026-01-05T07:00:00Z"), Instant.parse("2026-01-07T00:00:00Z")}) {
            for (long generation : new long[] {1, 2}) {
                var authority = journal.snapshot(cutoff, generation);
                var projected = view.snapshot(version, instrument, date, date, cutoff, generation);
                assertEquals(generation, projected.generation());
                assertEquals(authority.observations().stream()
                        .filter(row -> row.instrument().equals(instrument) && row.date().equals(date)).toList(),
                        projected.observations());
                var first = new ArrayList<DataFeed.Observation>();
                authority.replayFeed().replay(row -> {
                    if (row.instrument().equals(instrument) && row.date().equals(date)) first.add(row);
                });
                var replay = new ArrayList<DataFeed.Observation>();
                projected.replayFeed().replay(replay::add);
                assertEquals(first, replay);
            }
        }
    }
}
