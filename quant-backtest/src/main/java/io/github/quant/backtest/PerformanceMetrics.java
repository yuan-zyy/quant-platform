package io.github.quant.backtest;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;

public record PerformanceMetrics(BigDecimal endingEquity, BigDecimal totalReturn, BigDecimal maxDrawdown,
                                 BigDecimal totalFees, BigDecimal tradedNotional, int fillCount) {
    public record EquityPoint(Instant time, BigDecimal equity) {}

    static PerformanceMetrics calculate(BigDecimal initial, List<EquityPoint> curve,
                                        List<BacktestEngine.Fill> fills) {
        BigDecimal peak = initial;
        BigDecimal drawdown = BigDecimal.ZERO;
        BigDecimal ending = initial;
        for (EquityPoint point : curve) {
            ending = point.equity();
            peak = peak.max(ending);
            drawdown = drawdown.max(peak.subtract(ending).divide(peak, MathContext.DECIMAL128));
        }
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;
        for (var fill : fills) {
            fees = fees.add(fill.fee());
            notional = notional.add(fill.price().multiply(BigDecimal.valueOf(fill.quantity())));
        }
        return new PerformanceMetrics(ending, ending.divide(initial, MathContext.DECIMAL128).subtract(BigDecimal.ONE),
                drawdown, fees, notional, fills.size());
    }
}
