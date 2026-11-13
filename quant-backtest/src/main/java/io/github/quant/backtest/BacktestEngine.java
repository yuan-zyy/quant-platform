package io.github.quant.backtest;

import io.github.quant.market.Bar;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Minimal deterministic long-only daily close-to-close backtest. No real orders are sent. */
public final class BacktestEngine {
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
