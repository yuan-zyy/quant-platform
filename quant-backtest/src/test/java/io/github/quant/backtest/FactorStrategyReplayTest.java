package io.github.quant.backtest;

import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.Bar;
import io.github.quant.market.HistoricalDataFeed;
import io.github.quant.market.HistoricalDataFeed.Session;
import io.github.quant.market.factor.FactorStore;
import io.github.quant.market.factor.query.FactorQuery;
import io.github.quant.market.factor.query.FactorQueryPlanner;
import io.github.quant.oms.LimitOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactorStrategyReplayTest {
    @Test
    void usesOnlyFactorPublicationsVisibleAtTheStrategyDecision() {
        var stock = new Instrument("600000", "SSE");
        var price = BigDecimal.TEN;
        var monday = LocalDate.of(2026, 1, 5);
        var bars = List.of(monday, monday.plusDays(1), monday.plusDays(2)).stream()
                .map(day -> new Bar(day, price, price, price, price, 1000)).toList();
        var feed = new HistoricalDataFeed(bars.stream()
                .map(bar -> new Session(stock, bar, 100, HistoricalDataFeed.closeTime(bar))).toList());
        var factors = new FactorStore();
        var version = new FactorStore.FactorVersion("signal", "1");
        factors.register(new FactorStore.Definition(version, "Daily signal", "ratio", Instant.parse("2026-01-01T00:00:00Z")));
        var firstClose = HistoricalDataFeed.closeTime(bars.get(0));
        var secondClose = HistoricalDataFeed.closeTime(bars.get(1));
        factors.appendAll(version, List.of(
                new FactorStore.Value(stock, firstClose, firstClose.plusSeconds(1), BigDecimal.ONE),
                new FactorStore.Value(stock, secondClose, secondClose, BigDecimal.ONE)));
        long generation = factors.generation();
        var plan = FactorQueryPlanner.plan(new FactorQuery.Compare(FactorQuery.Comparison.GT,
                new FactorQuery.Reference(version), new FactorQuery.Literal(BigDecimal.ZERO)));

        var result = new BacktestEngine().run(feed, BigDecimal.valueOf(10_000),
                Map.of(stock, TradingRules.forType(SecurityType.A_SHARE)), ExecutionCosts.NONE,
                (close, context) -> plan.evaluate(factors, stock, close.eventTime(), context.processingTime(), generation)
                        .orElse(false)
                        ? List.of(new LimitOrder.Request(stock, LimitOrder.Side.BUY, 100, price))
                        : List.of());
        assertEquals(1, result.orders().size());
        assertEquals(1, result.fills().size());
        assertEquals(monday.plusDays(2), result.fills().getFirst().tradeDate());
    }
}
