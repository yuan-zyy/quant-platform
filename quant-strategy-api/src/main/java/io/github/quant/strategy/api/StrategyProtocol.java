package io.github.quant.strategy.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.quant.domain.Instrument;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Internal v1 JSON Lines messages exchanged with a Python strategy worker. */
public final class StrategyProtocol {
    public static final int VERSION = 1;
    public static final int MAX_LINE_BYTES = 1024 * 1024;

    private static final int MAX_DECIMAL_LENGTH = 128;
    private static final Instant FIRST_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LAST_INSTANT = Instant.parse("9999-12-31T23:59:59.999999999Z");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]{0,18}");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private StrategyProtocol() {}

    public enum Side { BUY, SELL }

    public record Request(String requestId, Event event, Portfolio portfolio) {
        public Request {
            requireText(requestId, "requestId");
            Objects.requireNonNull(event);
            Objects.requireNonNull(portfolio);
        }
    }

    public record Event(Instrument instrument, LocalDate date, Instant eventTime, Instant availableTime,
                        Instant processingTime, BigDecimal open, BigDecimal high, BigDecimal low,
                        BigDecimal close, long volume) {
        public Event {
            validateInstrument(instrument);
            int year = date.getYear();
            validateTime(eventTime);
            validateTime(availableTime);
            validateTime(processingTime);
            if (year < 1 || year > 9999 || eventTime.isAfter(availableTime)
                    || availableTime.isAfter(processingTime)) {
                throw new IllegalArgumentException("invalid event date or time order");
            }
            if (volume < 0 || low.signum() <= 0 || low.compareTo(open) > 0 || low.compareTo(close) > 0
                    || high.compareTo(open) < 0 || high.compareTo(close) < 0) {
                throw new IllegalArgumentException("invalid OHLCV");
            }
        }
    }

    public record Portfolio(BigDecimal cash, List<Position> positions) {
        public Portfolio {
            if (cash.signum() < 0) throw new IllegalArgumentException("negative cash");
            positions = List.copyOf(positions);
            var instruments = new HashSet<Instrument>();
            for (var position : positions) {
                if (!instruments.add(position.instrument())) {
                    throw new IllegalArgumentException("duplicate portfolio instrument");
                }
            }
        }
    }

    public record Position(Instrument instrument, long quantity) {
        public Position {
            validateInstrument(instrument);
            if (quantity <= 0) throw new IllegalArgumentException("position quantity must be positive");
        }
    }

    public sealed interface Response permits Orders, Failure {
        String requestId();
    }

    public record Orders(String requestId, List<Order> orders) implements Response {
        public Orders {
            requireText(requestId, "requestId");
            orders = List.copyOf(orders);
        }
    }

    public record Failure(String requestId, String code, String message) implements Response {
        public Failure {
            requireText(requestId, "requestId");
            requireText(code, "error code");
            requireText(message, "error message");
        }
    }

    public record Order(Instrument instrument, Side side, long quantity, BigDecimal limit) {
        public Order {
            validateInstrument(instrument);
            Objects.requireNonNull(side);
            if (quantity <= 0 || limit.signum() <= 0) throw new IllegalArgumentException("invalid order");
        }
    }

    /** Encodes one request, excluding the terminating LF. */
    public static String encode(Request request) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        root.put("requestId", request.requestId());
        Event event = request.event();
        ObjectNode eventJson = root.putObject("event");
        writeInstrument(eventJson, event.instrument());
        eventJson.put("date", event.date().toString());
        eventJson.put("eventTime", event.eventTime().toString());
        eventJson.put("availableTime", event.availableTime().toString());
        eventJson.put("processingTime", event.processingTime().toString());
        eventJson.put("open", decimalText(event.open()));
        eventJson.put("high", decimalText(event.high()));
        eventJson.put("low", decimalText(event.low()));
        eventJson.put("close", decimalText(event.close()));
        eventJson.put("volume", Long.toString(event.volume()));

        ObjectNode portfolioJson = root.putObject("portfolio");
        portfolioJson.put("cash", decimalText(request.portfolio().cash()));
        var positionsJson = portfolioJson.putArray("positions");
        var positions = new ArrayList<>(request.portfolio().positions());
        positions.sort(Comparator.comparing((Position position) -> position.instrument().exchange())
                .thenComparing(position -> position.instrument().symbol()));
        for (var position : positions) {
            ObjectNode item = positionsJson.addObject();
            writeInstrument(item, position.instrument());
            item.put("quantity", Long.toString(position.quantity()));
        }

        String line = root.toString();
        validateLine(line);
        return line;
    }

    /** Decodes one response and verifies the version and correlation ID. */
    public static Response decodeResponse(String line, String expectedRequestId) {
        validateLine(line);
        JsonNode root;
        try {
            root = JSON.readTree(line);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid JSON response", exception);
        }

        JsonNode version = root.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != VERSION) {
            throw new IllegalArgumentException("unsupported response version");
        }
        String requestId = readText(root, "requestId");
        if (!requestId.equals(expectedRequestId)) throw new IllegalArgumentException("response requestId mismatch");

        if (root.has("error")) {
            requireFields(root, "version", "requestId", "error");
            var error = root.get("error");
            requireFields(error, "code", "message");
            return new Failure(requestId, readText(error, "code"), readText(error, "message"));
        }

        requireFields(root, "version", "requestId", "orders");
        var ordersJson = root.get("orders");
        if (!ordersJson.isArray()) throw new IllegalArgumentException("orders must be an array");
        var orders = new ArrayList<Order>();
        for (JsonNode item : ordersJson) {
            requireFields(item, "instrument", "side", "quantity", "limit");
            var instrument = item.get("instrument");
            requireFields(instrument, "symbol", "exchange");
            orders.add(new Order(new Instrument(readText(instrument, "symbol"), readText(instrument, "exchange")),
                    Side.valueOf(readText(item, "side")),
                    readQuantity(item), readDecimal(item, "limit")));
        }
        return new Orders(requestId, orders);
    }

    private static void writeInstrument(ObjectNode parent, Instrument instrument) {
        var item = parent.putObject("instrument");
        item.put("symbol", instrument.symbol());
        item.put("exchange", instrument.exchange());
    }

    private static void validateInstrument(Instrument instrument) {
        requireText(instrument.symbol(), "symbol");
        requireText(instrument.exchange(), "exchange");
    }

    private static void validateTime(Instant time) {
        if (time.isBefore(FIRST_INSTANT) || time.isAfter(LAST_INSTANT)) {
            throw new IllegalArgumentException("timestamp year is outside the protocol range");
        }
    }

    private static void validateLine(String line) {
        if (line.length() > MAX_LINE_BYTES || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0
                || line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            throw new IllegalArgumentException("invalid JSON line framing");
        }
    }

    private static void requireFields(JsonNode node, String... fields) {
        if (!node.isObject() || node.size() != fields.length) {
            throw new IllegalArgumentException("unexpected object shape");
        }
        for (String field : fields) {
            if (!node.has(field)) throw new IllegalArgumentException("missing field " + field);
        }
    }

    private static String readText(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return requireText(value.textValue(), field);
    }

    private static String requireText(String value, String field) {
        if (value.isBlank() || value.codePoints().anyMatch(
                point -> point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static long readQuantity(JsonNode node) {
        String value = readText(node, "quantity");
        if (!POSITIVE_INTEGER.matcher(value).matches()) throw new IllegalArgumentException("invalid quantity");
        return Long.parseLong(value);
    }

    private static BigDecimal readDecimal(JsonNode node, String field) {
        String value = readText(node, field);
        if (value.length() > MAX_DECIMAL_LENGTH || !DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return new BigDecimal(value);
    }

    private static String decimalText(BigDecimal value) {
        // Check bounds before toPlainString expands negative scales or leading fractional zeroes.
        long scale = value.scale();
        long precision = value.precision();
        long length = scale > 0 ? Math.max(precision + 1, scale + 2) : precision - scale;
        if (value.signum() == 0 && scale <= 0) return "0";
        if (value.signum() < 0) length++;
        if (length > MAX_DECIMAL_LENGTH) throw new IllegalArgumentException("decimal exceeds protocol size");
        return value.toPlainString();
    }
}
