package io.github.quant.backtest;

import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.Bar;
import io.github.quant.market.HistoricalDataFeed;
import io.github.quant.market.HistoricalDataFeed.Session;
import io.github.quant.oms.LimitOrder.Request;
import io.github.quant.oms.LimitOrder.Side;
import io.github.quant.oms.LimitOrder.Status;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DailyReplayTest {
    private static final Instrument STOCK = new Instrument("600000", "SSE");
    private static final LocalDate MONDAY = LocalDate.of(2026, 1, 5);

    @Test
    void waitsForPublicationAndNeverTradesAtAnOpeningThatAlreadyOccurred() {
        Session monday = session(0, "10", "11", 100);
        Session tuesday = session(1, "10", "11", 100);
        Session wednesday = session(2, "12", "14", 100);
        var delayed = new Session(STOCK, monday.bar(), 100,
                HistoricalDataFeed.closeTime(tuesday.bar()).minus(Duration.ofHours(1)));
        var strategy = (BacktestEngine.Strategy) (close, context) -> {
            assertEquals(close.availableTime(), context.processingTime());
            if (!close.date().equals(MONDAY)) return List.of();
            assertTrue(context.processingTime().isAfter(close.eventTime()));
            return List.of(order(Side.BUY, 100, "20"));
        };

        var first = run(List.of(delayed, tuesday, wednesday), SecurityType.A_SHARE, ExecutionCosts.NONE, strategy);
        var reordered = run(List.of(wednesday, delayed, tuesday), SecurityType.A_SHARE, ExecutionCosts.NONE, strategy);

        assertEquals(first, reordered);
        assertEquals(1, first.fills().size());
        assertEquals(MONDAY.plusDays(2), first.fills().getFirst().tradeDate());
        assertEquals(Status.FILLED, first.orders().getFirst().status());
        money("10200", first.metrics().endingEquity());
        money("0.02", first.metrics().totalReturn());
    }

    @Test
    void enforcesTPlusOneByTradeDateAndSupportsSameDaySecurities() {
        var sessions = List.of(session(0, "10", "10", 200), session(1, "10", "10", 200),
                session(2, "10", "10", 200));
        BacktestEngine.Strategy strategy = (close, context) -> {
            if (close.date().equals(MONDAY)) return List.of(order(Side.BUY, 100, "10"), order(Side.SELL, 100, "10"));
            if (close.date().equals(MONDAY.plusDays(1)) && !context.portfolio().positions().isEmpty()) {
                return List.of(order(Side.SELL, 100, "10"));
            }
            return List.of();
        };

        var stock = run(sessions, SecurityType.A_SHARE, ExecutionCosts.NONE, strategy);
        assertEquals(Status.REJECTED, stock.orders().get(1).status());
        assertEquals(MONDAY.plusDays(2), stock.fills().getLast().tradeDate());
        assertTrue(stock.portfolio().positions().isEmpty());

        for (var type : List.of(SecurityType.SAME_DAY_ETF, SecurityType.CONVERTIBLE_BOND)) {
            var result = run(sessions, type, ExecutionCosts.NONE, strategy);
            assertEquals(Status.FILLED, result.orders().get(1).status());
            assertEquals(MONDAY.plusDays(1), result.fills().getLast().tradeDate());
            assertTrue(result.portfolio().positions().isEmpty());
        }
    }

    @Test
    void appliesLimitAfterSlippageAndSharesOpeningLiquidityAcrossOrders() {
        var costs = new ExecutionCosts(bd("0.001"), bd("2"), bd("0.01"));
        var result = run(List.of(session(0, "10", "10", 100), session(1, "10", "9", 150)),
                SecurityType.A_SHARE, costs, (close, context) -> close.date().equals(MONDAY)
                        ? List.of(order(Side.BUY, 100, "10"), order(Side.BUY, 200, "10.10"),
                                order(Side.BUY, 100, "10.10"), order(Side.BUY, 15, "10.10"))
                        : List.of());

        assertEquals(1, result.fills().size());
        var fill = result.fills().getFirst();
        assertEquals(2L, fill.orderId());
        assertEquals(100L, fill.quantity());
        money("10.10", fill.price());
        money("2.00", result.metrics().totalFees());
        money("1010", result.metrics().tradedNotional());
        money("8988", result.portfolio().cash());
        money("9888", result.metrics().endingEquity());
        money("0.0112", result.metrics().maxDrawdown());
        assertEquals(List.of(Status.CANCELED, Status.CANCELED, Status.CANCELED, Status.REJECTED),
                result.orders().stream().map(order -> order.status()).toList());
        assertEquals(100L, result.orders().get(1).filledQuantity());
    }

    @Test
    void rejectsUnaffordableFillsWithoutChangingPortfolioOrConsumingLiquidity() {
        var result = run(List.of(session(0, "10", "10", 0), session(1, "10", "10", 2000)),
                SecurityType.A_SHARE, ExecutionCosts.NONE, (close, context) -> close.date().equals(MONDAY)
                        ? List.of(order(Side.BUY, 1100, "10"), order(Side.BUY, 100, "10"))
                        : List.of());
        assertEquals(Status.REJECTED, result.orders().getFirst().status());
        assertEquals(Status.FILLED, result.orders().getLast().status());
        assertEquals(Map.of(STOCK, 100L), result.portfolio().positions());
        money("9000", result.portfolio().cash());
    }

    @Test
    void completesPartialFillAcrossSessionsAndRejectsDuplicateInput() {
        var sessions = List.of(session(0, "10", "10", 0), session(1, "10", "10", 100),
                session(2, "10", "10", 100));
        var result = run(sessions, SecurityType.A_SHARE, ExecutionCosts.NONE, (close, context) -> {
            if (close.date().equals(MONDAY)) return List.of(order(Side.BUY, 200, "10"));
            if (close.date().equals(MONDAY.plusDays(1))) {
                assertEquals(Map.of(STOCK, 100L), context.portfolio().positions());
            }
            return List.of();
        });
        assertEquals(Status.FILLED, result.orders().getFirst().status());
        assertEquals(2, result.fills().size());
        assertThrows(IllegalArgumentException.class,
                () -> run(List.of(sessions.getFirst(), sessions.getFirst()), SecurityType.A_SHARE,
                        ExecutionCosts.NONE, (close, context) -> fail("duplicate feed dispatched")));
    }

    private static BacktestEngine.ReplayResult run(List<Session> sessions, SecurityType type,
                                                   ExecutionCosts costs, BacktestEngine.Strategy strategy) {
        return new BacktestEngine().run(new HistoricalDataFeed(sessions), bd("10000"),
                Map.of(STOCK, TradingRules.forType(type)), costs, strategy);
    }

    private static Session session(int day, String open, String close, long liquidity) {
        BigDecimal first = bd(open);
        BigDecimal last = bd(close);
        var bar = new Bar(MONDAY.plusDays(day), first, first.max(last), first.min(last), last, 100_000);
        return new Session(STOCK, bar, liquidity, HistoricalDataFeed.closeTime(bar));
    }

    private static Request order(Side side, long quantity, String limit) {
        return new Request(STOCK, side, quantity, bd(limit));
    }

    private static void money(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), () -> "Expected " + expected + ", got " + actual);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
