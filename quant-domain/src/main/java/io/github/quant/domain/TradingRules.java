package io.github.quant.domain;

import java.util.Objects;

/** Simulation parameters for a particular instrument and historical period. */
public record TradingRules(boolean sameDayResale, long buyLotSize) {
    public TradingRules {
        if (buyLotSize <= 0) throw new IllegalArgumentException("buy lot size must be positive");
    }

    public static TradingRules forType(SecurityType type) {
        return switch (Objects.requireNonNull(type)) {
            case A_SHARE, DOMESTIC_EQUITY_ETF -> new TradingRules(false, 100);
            case SAME_DAY_ETF -> new TradingRules(true, 100);
            case CONVERTIBLE_BOND -> new TradingRules(true, 10);
        };
    }
}
