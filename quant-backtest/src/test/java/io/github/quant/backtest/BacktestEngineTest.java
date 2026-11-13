package io.github.quant.backtest;

import io.github.quant.market.Bar;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {
    @Test void computesBuyAndHoldReturn() {
        var bars = List.of(
            new Bar(LocalDate.of(2026, 1, 5), bd("10"), bd("11"), bd("9"), bd("10"), 100),
            new Bar(LocalDate.of(2026, 1, 6), bd("11"), bd("13"), bd("10"), bd("12"), 200));
        var result = new BacktestEngine().buyAndHold(bars, bd("10000"));
        assertEquals(0, result.endingEquity().compareTo(bd("12000")));
        assertEquals(0, result.totalReturn().compareTo(bd("0.2")));
    }
    @Test void rejectsOutOfOrderBars() {
        var b = new Bar(LocalDate.of(2026, 1, 5), bd("10"), bd("11"), bd("9"), bd("10"), 100);
        assertThrows(IllegalArgumentException.class, () -> new BacktestEngine().buyAndHold(List.of(b,b), bd("100")));
    }
    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
