package io.github.quant.market.ingest;

import io.github.quant.domain.Instrument;
import io.github.quant.market.Bar;
import io.github.quant.market.DataFeed;
import io.github.quant.market.VersionedMarketDataStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Imports daily opening and closing observations from a single-line CSV format. */
public final class MarketDataCsvImporter {
    private static final List<String> HEADER = List.of("dataset_version", "event_type", "symbol", "exchange",
            "session_date", "event_time", "available_time", "open", "high", "low", "close", "volume",
            "opening_quantity");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern QUANTITY = Pattern.compile("[0-9]+");
    private static final Pattern UTC_INSTANT = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?Z");

    /**
     * Reads and validates the entire import before appending it atomically to the target version.
     * The reader stays open. The returned count includes rows already present in the store.
     */
    public int importInto(Reader source, VersionedMarketDataStore store) throws IOException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(store);
        var reader = new BufferedReader(source);
        String header = reader.readLine();
        if (header != null && header.startsWith("\uFEFF")) header = header.substring(1);
        if (header == null || !readFields(header, 1).equals(HEADER)) {
            throw new IllegalArgumentException("CSV line 1: expected header " + String.join(",", HEADER));
        }

        var observations = new ArrayList<DataFeed.Observation>();
        String row;
        int line = 1;
        while ((row = reader.readLine()) != null) {
            observations.add(readObservation(readFields(row, ++line), store.datasetVersion(), line));
        }
        store.appendAll(observations);
        return observations.size();
    }

    private static DataFeed.Observation readObservation(List<String> fields, String version, int line) {
        try {
            if (fields.size() != HEADER.size()) throw new IllegalArgumentException("expected 13 fields");
            if (!fields.get(0).equals(version)) throw new IllegalArgumentException("dataset version mismatch");
            var instrument = new Instrument(fields.get(2), fields.get(3));
            var date = LocalDate.parse(fields.get(4));
            var eventTime = instant(fields, 5);
            var availableTime = instant(fields, 6);
            BigDecimal open = decimal(fields, 7);
            return switch (fields.get(1)) {
                case "OPEN" -> {
                    for (int index = 8; index <= 11; index++) requireEmpty(fields, index);
                    yield new DataFeed.Open(instrument, date, eventTime, availableTime, open, quantity(fields, 12));
                }
                case "CLOSE" -> {
                    requireEmpty(fields, 12);
                    var bar = new Bar(date, open, decimal(fields, 8), decimal(fields, 9), decimal(fields, 10),
                            quantity(fields, 11));
                    yield new DataFeed.Close(instrument, eventTime, availableTime, bar);
                }
                default -> throw new IllegalArgumentException("event_type must be OPEN or CLOSE");
            };
        } catch (IllegalArgumentException | DateTimeParseException failure) {
            throw new IllegalArgumentException("CSV line " + line + ": " + failure.getMessage(), failure);
        }
    }

    private static List<String> readFields(String row, int line) {
        var fields = new ArrayList<String>();
        int index = 0;
        while (true) {
            if (index < row.length() && row.charAt(index) == '"') {
                var value = new StringBuilder();
                index++;
                while (true) {
                    int end = row.indexOf('"', index);
                    if (end < 0) throw malformed(line);
                    value.append(row, index, end);
                    index = end + 1;
                    if (index == row.length() || row.charAt(index) != '"') break;
                    value.append('"');
                    index++;
                }
                fields.add(value.toString());
            } else {
                int end = row.indexOf(',', index);
                if (end < 0) end = row.length();
                String value = row.substring(index, end);
                if (value.indexOf('"') >= 0) throw malformed(line);
                fields.add(value);
                index = end;
            }
            if (index == row.length()) return fields;
            if (row.charAt(index++) != ',') throw malformed(line);
        }
    }

    private static IllegalArgumentException malformed(int line) {
        return new IllegalArgumentException("CSV line " + line + ": invalid quoting");
    }

    private static BigDecimal decimal(List<String> fields, int index) {
        String value = fields.get(index);
        if (!DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException(HEADER.get(index) + " must be a plain decimal");
        }
        return new BigDecimal(value);
    }

    private static long quantity(List<String> fields, int index) {
        String value = fields.get(index);
        if (!QUANTITY.matcher(value).matches()) {
            throw new IllegalArgumentException(HEADER.get(index) + " must be a nonnegative integer");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException(HEADER.get(index) + " exceeds the integer range", overflow);
        }
    }

    private static Instant instant(List<String> fields, int index) {
        String value = fields.get(index);
        if (!UTC_INSTANT.matcher(value).matches()) {
            throw new IllegalArgumentException(HEADER.get(index) + " must be an ISO UTC timestamp ending in Z");
        }
        return Instant.parse(value);
    }

    private static void requireEmpty(List<String> fields, int index) {
        if (!fields.get(index).isEmpty()) throw new IllegalArgumentException(HEADER.get(index) + " must be empty");
    }
}
