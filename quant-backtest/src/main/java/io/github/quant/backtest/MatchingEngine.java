package io.github.quant.backtest;

import io.github.quant.domain.Instrument;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.DataFeed.Open;
import io.github.quant.oms.LimitOrder;
import io.github.quant.oms.LimitOrder.Side;
import io.github.quant.portfolio.Portfolio;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class MatchingEngine {
    private final ExecutionCosts costs;

    MatchingEngine(ExecutionCosts costs) {
        this.costs = costs;
    }

    void match(Open opening, TradingRules rules, List<LimitOrder> orders, Portfolio portfolio,
               List<BacktestEngine.Fill> fills, Instant processingTime) {
        long liquidity = opening.availableQuantity();
        for (LimitOrder order : orders) {
            if (liquidity == 0) break;
            var request = order.request();
            // Orders must already exist at the market event, even when an opening is reported late.
            if (!order.active() || !request.instrument().equals(opening.instrument())
                    || !order.submittedAt().isBefore(opening.eventTime())) continue;

            BigDecimal price = costs.price(opening.price(), request.side());
            boolean buy = request.side() == Side.BUY;
            int relativeToLimit = price.compareTo(request.limit());
            if ((buy && relativeToLimit > 0) || (!buy && relativeToLimit < 0)) continue;

            long quantity = Math.min(liquidity, order.remaining());
            if (buy) quantity -= quantity % rules.buyLotSize();
            if (quantity == 0) continue;

            BigDecimal notional = price.multiply(BigDecimal.valueOf(quantity));
            BigDecimal fee = costs.fee(notional);
            boolean booked = buy
                    ? portfolio.buy(opening.instrument(), opening.date(), quantity, notional.add(fee))
                    : portfolio.sell(opening.instrument(), opening.date(), rules.sameDayResale(),
                            quantity, notional.subtract(fee));
            if (!booked) {
                order.reject(buy ? "insufficient cash" : "insufficient sellable position or cash");
                continue;
            }

            order.fill(quantity);
            liquidity -= quantity;
            fills.add(new BacktestEngine.Fill(order.snapshot().id(), opening.instrument(), request.side(), quantity,
                    price, fee, opening.date(), opening.eventTime(), processingTime));
        }
    }
}
