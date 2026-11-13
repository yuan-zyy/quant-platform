package io.github.quant.worker;

import io.github.quant.backtest.BacktestEngine;
import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.Bar;
import io.github.quant.market.HistoricalDataFeed;
import io.github.quant.market.HistoricalDataFeed.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PythonBacktestStrategyTest {
    @Test
    @EnabledIfSystemProperty(named = "quant.python", matches = ".+")
    void executesPythonStrategyThroughDailyReplay() throws Exception {
        var instrument = new Instrument("600000", "SSE");
        var price = BigDecimal.TEN;
        var first = new Bar(LocalDate.of(2026, 1, 5), price, price, price, price, 1000);
        var second = new Bar(first.date().plusDays(1), price, price, price, price, 1000);
        var feed = new HistoricalDataFeed(List.of(
                new Session(instrument, first, 100, HistoricalDataFeed.closeTime(first)),
                new Session(instrument, second, 100, HistoricalDataFeed.closeTime(second))));

        var command = List.of(System.getProperty("quant.python"), "-m", "quant_strategy",
                "quant_strategy.examples:buy_once");
        var process = StrategyProcess.start(command, Path.of("..", "python-sdk").toAbsolutePath().normalize(),
                Duration.ofSeconds(10));
        try (var strategy = new PythonBacktestStrategy(process)) {
            var result = new BacktestEngine().run(feed, BigDecimal.valueOf(10_000),
                    Map.of(instrument, TradingRules.forType(SecurityType.A_SHARE)), ExecutionCosts.NONE, strategy);
            assertEquals(1, result.orders().size());
            assertEquals(1, result.fills().size());
            assertEquals(second.date(), result.fills().getFirst().tradeDate());
            assertEquals(Map.of(instrument, 100L), result.portfolio().positions());
        }
    }
}
