package io.github.quant.strategy.api;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.quant.domain.Instrument;
import io.github.quant.strategy.api.StrategyProtocol.Event;
import io.github.quant.strategy.api.StrategyProtocol.Failure;
import io.github.quant.strategy.api.StrategyProtocol.Order;
import io.github.quant.strategy.api.StrategyProtocol.Orders;
import io.github.quant.strategy.api.StrategyProtocol.Portfolio;
import io.github.quant.strategy.api.StrategyProtocol.Position;
import io.github.quant.strategy.api.StrategyProtocol.Request;
import io.github.quant.strategy.api.StrategyProtocol.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyProtocolTest {
    private static final Instrument STOCK = new Instrument("600000", "SSE");
    private static final Instrument FUND = new Instrument("510300", "SSE");
    private static final Instant CLOSE_TIME = Instant.parse("2026-11-13T07:00:00Z");
    private static final Instant AVAILABLE_TIME = CLOSE_TIME.plusNanos(123456789);
    private static final Instant PROCESSING_TIME = AVAILABLE_TIME.plusSeconds(1);
    private static final BigDecimal PRICE = new BigDecimal("8.100");
    private static final String ORDER = """
            {"instrument":{"symbol":"600000","exchange":"SSE"},"side":"BUY","quantity":"100","limit":"8.100"}""";

    @Test
    void encodesPreciseDataAndCanonicalizesPositions() throws Exception {
        var request = new Request("id\n1", event(PROCESSING_TIME),
                new Portfolio(new BigDecimal("1E+3"), List.of(new Position(STOCK, 100), new Position(FUND, 10))));
        String wire = StrategyProtocol.encode(request);
        assertTrue(wire.lines().count() == 1);

        var json = JsonMapper.builder().build().readTree(wire);
        assertEquals(1, json.path("version").intValue());
        assertEquals("id\n1", json.path("requestId").textValue());
        var event = json.path("event");
        assertEquals("2026-11-13", event.path("date").textValue());
        assertEquals(CLOSE_TIME.toString(), event.path("eventTime").textValue());
        assertEquals(AVAILABLE_TIME.toString(), event.path("availableTime").textValue());
        assertEquals(PROCESSING_TIME.toString(), event.path("processingTime").textValue());
        assertEquals("8.100", event.path("close").textValue());
        assertEquals(Long.toString(Long.MAX_VALUE), event.path("volume").textValue());
        assertEquals("1000", json.path("portfolio").path("cash").textValue());
        assertEquals("510300", json.at("/portfolio/positions/0/instrument/symbol").textValue());
        assertEquals("10", json.at("/portfolio/positions/0/quantity").textValue());
    }

    @Test
    void decodesCorrelatedOrderAndFailure() {
        assertEquals(new Orders("42", List.of(new Order(STOCK, Side.BUY, 100, PRICE))),
                StrategyProtocol.decodeResponse(orders(ORDER), "42"));
        assertEquals(new Failure("42", "STRATEGY_ERROR", "例外"),
                StrategyProtocol.decodeResponse("""
                        {"version":1,"requestId":"42","error":{"code":"STRATEGY_ERROR","message":"例外"}}""", "42"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"version\":2,\"requestId\":\"42\",\"orders\":[]}",
            "{\"version\":1.0,\"requestId\":\"42\",\"orders\":[]}",
            "{\"version\":1,\"version\":1,\"requestId\":\"42\",\"orders\":[]}",
            "{\"version\":1,\"requestId\":\"43\",\"orders\":[]}",
            "{\"version\":1,\"requestId\":\"42\",\"orders\":[]}{}",
            "{\"version\":1,\"requestId\":\"42\",\"orders\":{},\"error\":{}}",
            "{\"version\":1,\"requestId\":\"42\",\"orders\":[],\"extra\":null}",
            "{\"version\":1,\"requestId\":\"42\",\"orders\":null}",
            "null",
            ""
    })
    void rejectsInvalidResponseEnvelopes(String line) {
        assertThrows(IllegalArgumentException.class, () -> StrategyProtocol.decodeResponse(line, "42"));
    }

    @Test
    void rejectsInvalidOrdersAndOverlongLines() {
        for (String order : List.of(
                ORDER.replace("\"100\"", "100"),
                ORDER.replace("\"100\"", "\"0100\""),
                ORDER.replace("\"100\"", "\"9223372036854775808\""),
                ORDER.replace("BUY", "SHORT"),
                ORDER.replace("8.100", "8E-2"),
                ORDER.replace("8.100", "0"),
                ORDER.replace("8.100", "1".repeat(129)))) {
            assertThrows(IllegalArgumentException.class, () -> StrategyProtocol.decodeResponse(orders(order), "42"));
        }
        assertThrows(IllegalArgumentException.class, () -> StrategyProtocol.decodeResponse(orders(ORDER) + "\n", "42"));
        String hugeMessage = "{\"version\":1,\"requestId\":\"42\",\"error\":{\"code\":\"X\",\"message\":\""
                + "例".repeat(StrategyProtocol.MAX_LINE_BYTES / 3) + "\"}}";
        assertThrows(IllegalArgumentException.class, () -> StrategyProtocol.decodeResponse(hugeMessage, "42"));
    }

    @Test
    void rejectsInvalidTimeOrderingAndOversizedRequestDecimal() {
        assertThrows(IllegalArgumentException.class, () -> event(CLOSE_TIME));
        var request = new Request("42", event(PROCESSING_TIME),
                new Portfolio(new BigDecimal("1E+2147483647"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> StrategyProtocol.encode(request));
    }

    private static Event event(Instant processingTime) {
        return new Event(STOCK, LocalDate.of(2026, 11, 13), CLOSE_TIME, AVAILABLE_TIME,
                processingTime, PRICE, PRICE, PRICE, PRICE, Long.MAX_VALUE);
    }

    private static String orders(String order) {
        return "{\"version\":1,\"requestId\":\"42\",\"orders\":[" + order + "]}";
    }
}
