package io.github.quant.backtest;

import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.DataFeed;
import io.github.quant.market.VersionedMarketDataStore;
import io.github.quant.market.ingest.MarketDataCsvImporter;
import io.github.quant.oms.LimitOrder;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportedMarketReplayTest {
    private static final Instrument STOCK = new Instrument("600000", "SSE");
    private static final String HEADER =
            "dataset_version,event_type,symbol,exchange,session_date,event_time,available_time,"
                    + "open,high,low,close,volume,opening_quantity\n";

    @Test
    void importedCorrectionsAreVisibleToSnapshotsWithoutChangingHistoricalDecisions() throws Exception {
        var store = new VersionedMarketDataStore("daily-v1");
        var importer = new MarketDataCsvImporter();
        assertEquals(3, importer.importInto(new StringReader(HEADER + """
                daily-v1,OPEN,600000,SSE,2026-01-06,2026-01-06T01:30:00Z,2026-01-06T01:30:00Z,10,,,,,100
                daily-v1,CLOSE,600000,SSE,2026-01-05,2026-01-05T07:00:00Z,2026-01-05T07:01:00Z,10,11,9,10,1000,
                daily-v1,CLOSE,600000,SSE,2026-01-06,2026-01-06T07:00:00Z,2026-01-06T07:01:00Z,10,11,9,11,1000,
                """), store));
        var cutoff = Instant.parse("2026-01-07T00:00:00Z");
        var first = store.snapshot(cutoff);
        assertEquals(1, importer.importInto(new StringReader(HEADER + """
                daily-v1,CLOSE,600000,SSE,2026-01-05,2026-01-05T07:00:00Z,2026-01-06T07:05:00Z,10,19,9,19,1000,
                """), store));
        var revised = store.snapshot(cutoff);

        var corrected = revised.observations().stream()
                .filter(DataFeed.Close.class::isInstance)
                .map(DataFeed.Close.class::cast)
                .filter(close -> close.date().equals(LocalDate.of(2026, 1, 5)))
                .findFirst().orElseThrow();
        assertEquals(BigDecimal.valueOf(19), corrected.bar().close());
        assertEquals(first.observations(), store.snapshot(cutoff, first.generation()).observations());

        BacktestEngine.Strategy strategy = (close, context) ->
                close.bar().close().equals(BigDecimal.TEN)
                        ? List.of(new LimitOrder.Request(STOCK, LimitOrder.Side.BUY, 100, BigDecimal.TEN))
                        : List.of();
        var expected = replay(first.replayFeed(), strategy);
        var actual = replay(revised.replayFeed(), strategy);
        assertEquals(expected, actual);
        assertEquals(1, actual.fills().size());
        assertEquals(LocalDate.of(2026, 1, 6), actual.fills().getFirst().tradeDate());
        assertEquals(Map.of(STOCK, 100L), actual.portfolio().positions());
    }

    private static BacktestEngine.ReplayResult replay(DataFeed feed, BacktestEngine.Strategy strategy) {
        return new BacktestEngine().run(feed, BigDecimal.valueOf(10_000),
                Map.of(STOCK, TradingRules.forType(SecurityType.A_SHARE)), ExecutionCosts.NONE, strategy);
    }
}
