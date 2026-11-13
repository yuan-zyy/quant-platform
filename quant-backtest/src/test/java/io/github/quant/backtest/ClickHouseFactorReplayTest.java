package io.github.quant.backtest;

import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.Bar;
import io.github.quant.market.HistoricalDataFeed;
import io.github.quant.market.HistoricalDataFeed.Session;
import io.github.quant.market.clickhouse.ClickHouseFactorStore;
import io.github.quant.market.factor.FactorStore;
import io.github.quant.market.factor.query.FactorQuery;
import io.github.quant.market.factor.query.FactorQueryPlanner;
import io.github.quant.oms.LimitOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "quant.clickhouse.url", matches = "https?://.+")
class ClickHouseFactorReplayTest {
    @TempDir Path directory;

    @Test
    void restoresFactorDefinitionsAndUsesAPinnedCrossVersionViewInReplay() throws Exception {
        var endpoint = URI.create(System.getProperty("quant.clickhouse.url"));
        String user = System.getenv("QUANT_CLICKHOUSE_USER");
        Map<String, String> headers = user == null ? Map.of() : Map.of("X-ClickHouse-User", user,
                "X-ClickHouse-Key", System.getenv("QUANT_CLICKHOUSE_PASSWORD"));
        String dataset = UUID.randomUUID().toString();
        Path lock = directory.resolve("factors.lock");
        var factors = new ClickHouseFactorStore(endpoint, dataset, lock, headers);
        var stock = new Instrument("600000", "SSE");
        var price = BigDecimal.TEN;
        var monday = LocalDate.of(2026, 1, 5);
        var bars = List.of(monday, monday.plusDays(1), monday.plusDays(2)).stream()
                .map(day -> new Bar(day, price, price, price, price, 1000)).toList();
        var firstClose = HistoricalDataFeed.closeTime(bars.get(0));
        var secondClose = HistoricalDataFeed.closeTime(bars.get(1));
        var signal = new FactorStore.FactorVersion("signal", "1");
        var threshold = new FactorStore.FactorVersion("threshold", "2");
        var definition = new FactorStore.Definition(signal, "Daily signal", "ratio", firstClose);
        assertEquals(1, factors.register(definition));
        assertEquals(1, factors.register(definition));
        assertEquals(2, factors.register(new FactorStore.Definition(threshold, "Threshold", "ratio", firstClose)));
        assertEquals(3, factors.appendAll(signal, List.of(
                new FactorStore.Value(stock, firstClose, firstClose.plusNanos(1), new BigDecimal("2.0000")),
                new FactorStore.Value(stock, secondClose, secondClose, new BigDecimal("2.0000")))));
        assertEquals(4, factors.appendAll(threshold, List.of(
                new FactorStore.Value(stock, firstClose, firstClose, BigDecimal.ONE),
                new FactorStore.Value(stock, secondClose, secondClose, BigDecimal.ONE))));
        assertThrows(IllegalArgumentException.class, () -> factors.appendAll(signal, List.of(
                new FactorStore.Value(stock, secondClose, secondClose.plusNanos(1), BigDecimal.ONE),
                new FactorStore.Value(stock, secondClose, secondClose, BigDecimal.ZERO))));

        var reopened = new ClickHouseFactorStore(endpoint, dataset, lock, headers);
        var frozen = reopened.readView();
        assertEquals(4, frozen.generation());
        assertEquals(new BigDecimal("2.0000"), frozen.snapshot(signal, secondClose, 4).values().getLast().value());
        assertEquals(5, factors.appendAll(signal, List.of(
                new FactorStore.Value(stock, secondClose, secondClose.plusNanos(1), BigDecimal.ZERO))));

        var plan = FactorQueryPlanner.plan(new FactorQuery.Compare(FactorQuery.Comparison.GT,
                new FactorQuery.Reference(signal), new FactorQuery.Reference(threshold)));
        assertTrue(plan.evaluate(frozen, stock, secondClose, secondClose.plusSeconds(1), 4).orElseThrow());
        assertFalse(plan.evaluate(reopened.readView(), stock, secondClose, secondClose.plusSeconds(1), 5).orElseThrow());

        var feed = new HistoricalDataFeed(bars.stream()
                .map(bar -> new Session(stock, bar, 100, HistoricalDataFeed.closeTime(bar))).toList());
        var result = new BacktestEngine().run(feed, BigDecimal.valueOf(10_000),
                Map.of(stock, TradingRules.forType(SecurityType.A_SHARE)), ExecutionCosts.NONE,
                (close, context) -> plan.evaluate(frozen, stock, close.eventTime(), context.processingTime(), 4).orElse(false)
                        ? List.of(new LimitOrder.Request(stock, LimitOrder.Side.BUY, 100, price))
                        : List.of());
        assertEquals(1, result.fills().size());
        assertEquals(monday.plusDays(2), result.fills().getFirst().tradeDate());
    }
}
