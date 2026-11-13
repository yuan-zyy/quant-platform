package io.github.quant.backtest;

import io.github.quant.domain.Instrument;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.Bar;
import io.github.quant.market.DataFeed;
import io.github.quant.oms.LimitOrder;
import io.github.quant.portfolio.Portfolio;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic daily backtest and legacy close-to-close return calculator. */
public final class BacktestEngine {
    @FunctionalInterface
    public interface Strategy {
        List<LimitOrder.Request> onClose(DataFeed.Close close, Context context);
    }

    public record Context(Instant processingTime, Portfolio.Snapshot portfolio) {}

    public record Fill(long orderId, Instrument instrument, LimitOrder.Side side, long quantity,
                       BigDecimal price, BigDecimal fee, LocalDate tradeDate,
                       Instant eventTime, Instant processingTime) {}

    public record ReplayResult(Portfolio.Snapshot portfolio, List<LimitOrder.Snapshot> orders,
                               List<Fill> fills, List<PerformanceMetrics.EquityPoint> equity,
                               PerformanceMetrics metrics) {
        public ReplayResult {
            orders = List.copyOf(orders);
            fills = List.copyOf(fills);
            equity = List.copyOf(equity);
        }
    }

    public ReplayResult run(DataFeed feed, BigDecimal startingCash, Map<Instrument, TradingRules> rules,
                            ExecutionCosts costs, Strategy strategy) {
        return new DailyReplay(startingCash, rules, costs, strategy).run(feed);
    }

    public record Result(BigDecimal startingEquity, BigDecimal endingEquity, BigDecimal totalReturn) {}
    public Result buyAndHold(List<Bar> bars, BigDecimal startingEquity) {
        Objects.requireNonNull(bars);
        Objects.requireNonNull(startingEquity);
        if (bars.isEmpty() || startingEquity.signum() <= 0) throw new IllegalArgumentException("bars and positive equity required");
        for (int i = 1; i < bars.size(); i++) {
            if (!bars.get(i).date().isAfter(bars.get(i - 1).date()))
                throw new IllegalArgumentException("bars must be strictly date-ascending");
        }
        BigDecimal first = bars.get(0).close();
        BigDecimal last = bars.get(bars.size() - 1).close();
        BigDecimal ending = startingEquity.multiply(last).divide(first, 8, java.math.RoundingMode.HALF_UP);
        BigDecimal ret = last.divide(first, 8, java.math.RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
        return new Result(startingEquity, ending, ret);
    }
}
