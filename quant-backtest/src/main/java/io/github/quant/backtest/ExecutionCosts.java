package io.github.quant.backtest;

import io.github.quant.oms.LimitOrder.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Configurable per-fill fee and adverse opening-price slippage. Rates are fractions of notional. */
public record ExecutionCosts(BigDecimal feeRate, BigDecimal minimumFee, BigDecimal slippageRate) {
    public static final ExecutionCosts NONE = new ExecutionCosts(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public ExecutionCosts {
        if (feeRate.signum() < 0 || minimumFee.signum() < 0
                || slippageRate.signum() < 0 || slippageRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("invalid execution costs");
        }
    }

    BigDecimal price(BigDecimal open, Side side) {
        Objects.requireNonNull(side);
        BigDecimal multiplier = side == Side.BUY
                ? BigDecimal.ONE.add(slippageRate) : BigDecimal.ONE.subtract(slippageRate);
        return open.multiply(multiplier);
    }

    BigDecimal fee(BigDecimal notional) {
        return notional.multiply(feeRate).max(minimumFee).setScale(2, RoundingMode.HALF_UP);
    }
}
