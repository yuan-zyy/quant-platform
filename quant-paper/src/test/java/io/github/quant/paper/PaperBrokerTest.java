package io.github.quant.paper;

import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.DataFeed;
import io.github.quant.oms.LimitOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;

import static io.github.quant.oms.LimitOrder.Side.BUY;
import static io.github.quant.oms.LimitOrder.Side.SELL;
import static io.github.quant.oms.LimitOrder.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperBrokerTest {
    private static final Instrument STOCK = new Instrument("600000", "SSE");
    private static final LocalDate MONDAY = LocalDate.of(2026, 1, 5);
    private static final BigDecimal PRICE = BigDecimal.TEN;

    @Test
    void matchesInSubmissionOrderAndReplaysOpeningAtMostOnce() {
        var broker = broker("10000");
        var monday = opening(MONDAY, 150);
        var preOpen = monday.eventTime().minusSeconds(1);
        var buyTwoLots = order(BUY, 200);
        var first = broker.submit("first", buyTwoLots, preOpen);
        var second = broker.submit("second", order(BUY, 100), preOpen);
        assertEquals(OPEN, first.status());
        assertEquals(OPEN, second.status());
        assertEquals(first, broker.submit("first", buyTwoLots, preOpen));
        assertThrows(IllegalArgumentException.class, () -> broker.submit("first", order(BUY, 100), preOpen));

        var fills = broker.onOpen(monday);
        assertEquals(1, fills.size());
        assertEquals(first.id(), fills.getFirst().orderId());
        assertEquals(100, fills.getFirst().quantity());
        var partial = broker.order("first").orElseThrow();
        assertEquals(PARTIALLY_FILLED, partial.status());
        assertEquals(partial, broker.submit("first", buyTwoLots, preOpen));
        assertTrue(broker.onOpen(monday).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> broker.onOpen(opening(MONDAY, 250)));
        assertEquals(OPEN, broker.order("second").orElseThrow().status());
        assertEquals(100L, broker.portfolio().positions().get(STOCK));

        var canceled = broker.cancel("first", monday.availableTime()).orElseThrow();
        assertEquals(CANCELED, canceled.status());
        assertEquals(100, canceled.filledQuantity());
        assertEquals(canceled, broker.cancel("first", monday.availableTime()).orElseThrow());
        assertTrue(broker.cancel("unknown", monday.availableTime()).isEmpty());
        var tuesday = opening(MONDAY.plusDays(1), 200);
        var next = broker.onOpen(tuesday);
        assertEquals(1, next.size());
        assertEquals(second.id(), next.getFirst().orderId());
        var filled = broker.order("second").orElseThrow();
        assertEquals(FILLED, filled.status());
        assertEquals(filled, broker.cancel("second", tuesday.availableTime()).orElseThrow());
        assertEquals(200L, broker.portfolio().positions().get(STOCK));
        assertEquals(0, broker.portfolio().cash().compareTo(new BigDecimal("8000")));
    }

    @Test
    void enforcesCashAndAshareNextSessionResale() {
        var broker = broker("1000");
        var monday = opening(MONDAY, 300);
        var before = monday.eventTime().minusSeconds(1);
        var purchase = broker.submit("purchase", order(BUY, 100), before);
        broker.submit("too-expensive", order(BUY, 100), before);
        broker.submit("too-soon", order(SELL, 100), before);
        var fills = broker.onOpen(monday);
        assertEquals(1, fills.size());
        assertEquals(purchase.id(), fills.getFirst().orderId());
        assertEquals(REJECTED, broker.order("too-expensive").orElseThrow().status());
        assertEquals(REJECTED, broker.order("too-soon").orElseThrow().status());
        assertEquals(0, broker.portfolio().cash().signum());
        assertEquals(100L, broker.portfolio().positions().get(STOCK));

        var sale = broker.submit("sale", order(SELL, 100), monday.availableTime());
        var next = broker.onOpen(opening(MONDAY.plusDays(1), 100));
        assertEquals(1, next.size());
        assertEquals(sale.id(), next.getFirst().orderId());
        assertTrue(broker.portfolio().positions().isEmpty());
        assertEquals(0, broker.portfolio().cash().compareTo(new BigDecimal("1000")));
    }

    @Test
    void skipsOrdersSubmittedAtOrAfterEventWhenOpeningArrivesLate() {
        var broker = broker("10000");
        var monday = opening(MONDAY, 200);
        var delayed = new DataFeed.Open(STOCK, MONDAY, monday.eventTime(),
                monday.eventTime().plusSeconds(2), PRICE, 200);
        broker.submit("on-event", order(BUY, 100), delayed.eventTime());
        broker.submit("after-event", order(BUY, 100), delayed.eventTime().plusSeconds(1));
        assertTrue(broker.onOpen(delayed).isEmpty());
        assertEquals(OPEN, broker.order("on-event").orElseThrow().status());
        assertEquals(OPEN, broker.order("after-event").orElseThrow().status());
        assertTrue(broker.portfolio().positions().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> broker.submit("out-of-order", order(BUY, 100), delayed.eventTime()));
        assertEquals(2, broker.onOpen(opening(MONDAY.plusDays(1), 200)).size());
    }

    @Test
    void appliesCancellationBeforeDelayedPublicationAndPreservesOtherOrders() {
        var broker = broker("1015");
        var monday = opening(MONDAY, 200);
        var before = monday.eventTime().minusSeconds(1);
        var delayed = new DataFeed.Open(STOCK, MONDAY, monday.eventTime(),
                monday.eventTime().plusSeconds(2), PRICE, 200);
        broker.submit("cancel-before-publication", order(BUY, 100), before);
        var remaining = broker.submit("fill-at-publication", order(BUY, 100), before);
        assertEquals(CANCELED,
                broker.cancel("cancel-before-publication", monday.eventTime().plusSeconds(1)).orElseThrow().status());

        var fills = broker.onOpen(delayed);
        assertEquals(1, fills.size());
        assertEquals(remaining.id(), fills.getFirst().orderId());
        assertEquals(delayed.eventTime(), fills.getFirst().eventTime());
        assertEquals(delayed.availableTime(), fills.getFirst().processingTime());
        assertEquals(0, broker.portfolio().cash().compareTo(new BigDecimal("15")));
    }

    private static PaperBroker broker(String cash) {
        return new PaperBroker(new BigDecimal(cash), Map.of(STOCK, TradingRules.forType(SecurityType.A_SHARE)),
                ExecutionCosts.NONE);
    }

    private static LimitOrder.Request order(LimitOrder.Side side, long quantity) {
        return new LimitOrder.Request(STOCK, side, quantity, PRICE);
    }

    private static DataFeed.Open opening(LocalDate date, long liquidity) {
        Instant time = date.atTime(LocalTime.of(1, 30)).toInstant(ZoneOffset.UTC);
        return new DataFeed.Open(STOCK, date, time, time, PRICE, liquidity);
    }
}
