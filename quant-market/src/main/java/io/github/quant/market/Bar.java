package io.github.quant.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Daily OHLCV bar. All prices are positive and the volume is nonnegative. */
public record Bar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
    public Bar {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        if (low.signum() <= 0 || open.signum() <= 0 || close.signum() <= 0 || high.signum() <= 0 || volume < 0)
            throw new IllegalArgumentException("invalid bar");
        if (high.compareTo(low) < 0 || high.compareTo(open) < 0 || high.compareTo(close) < 0
                || low.compareTo(open) > 0 || low.compareTo(close) > 0)
            throw new IllegalArgumentException("inconsistent OHLC");
    }
}
