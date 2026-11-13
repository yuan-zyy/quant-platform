package io.github.quant.paper;

import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.DataFeed;
import io.github.quant.oms.LimitOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JournaledPaperBrokerTest {
    private static final Instrument STOCK = new Instrument("600000", "SSE");
    private static final Map<Instrument, TradingRules> RULES =
            Map.of(STOCK, TradingRules.forType(SecurityType.A_SHARE));
    private static final BigDecimal CASH = new BigDecimal("10000.00");
    private static final BigDecimal PRICE = new BigDecimal("10.00");
    private static final Instant MONDAY_OPEN = Instant.parse("2026-01-05T01:30:00Z");
    private static final DataFeed.Open MONDAY = new DataFeed.Open(STOCK, LocalDate.of(2026, 1, 5),
            MONDAY_OPEN, MONDAY_OPEN, PRICE, 100);
    private static final Instant TUESDAY_OPEN = Instant.parse("2026-01-06T01:30:00Z");
    private static final DataFeed.Open TUESDAY = new DataFeed.Open(STOCK, LocalDate.of(2026, 1, 6),
            TUESDAY_OPEN, TUESDAY_OPEN, PRICE, 100);
    private static final Instant SUBMITTED = MONDAY_OPEN.minusSeconds(1);
    private static final LimitOrder.Request BUY = new LimitOrder.Request(STOCK, LimitOrder.Side.BUY, 200, PRICE);

    @TempDir Path directory;

    @Test
    void replaysPartialFillAndPendingOrderAcrossRestartsWithoutDoubleCounting() throws Exception {
        var journal = directory.resolve("account.journal");
        LimitOrder.Snapshot partial;
        try (var broker = open(journal)) {
            var submitted = broker.submit("buy", BUY, SUBMITTED);
            var fills = broker.onOpen(MONDAY);
            assertEquals(1, fills.size());
            assertEquals(submitted.id(), fills.getFirst().orderId());
            partial = broker.order("buy").orElseThrow();
            assertEquals(LimitOrder.Status.PARTIALLY_FILLED, partial.status());
            assertEquals(100, partial.filledQuantity());
        }
        try (var broker = open(journal)) {
            assertEquals(partial, broker.order("buy").orElseThrow());
            assertEquals(partial, broker.submit("buy", BUY, SUBMITTED));
            assertTrue(broker.onOpen(MONDAY).isEmpty());
            assertEquals(Map.of(STOCK, 100L), broker.portfolio().positions());
            assertEquals(0, broker.portfolio().cash().compareTo(new BigDecimal("9000")));
            assertThrows(IllegalArgumentException.class, () -> broker.submit("buy",
                    new LimitOrder.Request(STOCK, LimitOrder.Side.BUY, 100, PRICE), SUBMITTED));
            assertEquals(1, broker.onOpen(TUESDAY).size());
            var completed = broker.order("buy").orElseThrow();
            assertEquals(LimitOrder.Status.FILLED, completed.status());
            assertEquals(200, completed.filledQuantity());
            broker.submit("later", BUY, TUESDAY_OPEN);
            assertEquals(LimitOrder.Status.CANCELED, broker.cancel("later", TUESDAY_OPEN).orElseThrow().status());
        }
        try (var broker = open(journal)) {
            assertEquals(LimitOrder.Status.FILLED, broker.order("buy").orElseThrow().status());
            assertEquals(LimitOrder.Status.CANCELED, broker.order("later").orElseThrow().status());
            assertEquals(Map.of(STOCK, 200L), broker.portfolio().positions());
            assertEquals(0, broker.portfolio().cash().compareTo(new BigDecimal("8000")));
        }
    }

    @Test
    void recoversIncompleteTailAndContinuesAppending() throws Exception {
        var journal = directory.resolve("torn.journal");
        try (var broker = open(journal)) {
            broker.submit("buy", BUY, SUBMITTED);
        }
        long committedLength = Files.size(journal);
        Files.write(journal, new byte[] {1, 2}, StandardOpenOption.APPEND);
        try (var broker = open(journal)) {
            assertEquals(committedLength, Files.size(journal));
            assertEquals(LimitOrder.Status.OPEN, broker.order("buy").orElseThrow().status());
            assertEquals(1, broker.onOpen(MONDAY).size());
        }
        try (var broker = open(journal)) {
            assertEquals(100, broker.order("buy").orElseThrow().filledQuantity());
        }
    }

    @Test
    void rejectsCorruptionInACompleteFrameWithoutTruncatingIt() throws Exception {
        var journal = directory.resolve("corrupt.journal");
        try (var broker = open(journal)) {
            broker.submit("buy", BUY, SUBMITTED);
        }
        byte[] corrupted = Files.readAllBytes(journal);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(journal, corrupted);
        assertThrows(IOException.class, () -> open(journal));
        assertArrayEquals(corrupted, Files.readAllBytes(journal));
    }

    @Test
    void enforcesExclusiveWriterAndChecksOpeningConfiguration() throws Exception {
        var journal = directory.resolve("locked.journal");
        try (var first = open(journal)) {
            assertThrows(IOException.class, () -> open(journal));
            first.submit("buy", BUY, SUBMITTED);
        }
        assertThrows(IOException.class,
                () -> JournaledPaperBroker.open(journal, CASH.add(BigDecimal.ONE), RULES, ExecutionCosts.NONE));
        try (var reopened = open(journal)) {
            assertEquals(LimitOrder.Status.OPEN, reopened.order("buy").orElseThrow().status());
        }
    }

    private static JournaledPaperBroker open(Path journal) throws IOException {
        return JournaledPaperBroker.open(journal, CASH, RULES, ExecutionCosts.NONE);
    }
}
