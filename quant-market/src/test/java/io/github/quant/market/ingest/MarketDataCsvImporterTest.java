package io.github.quant.market.ingest;

import io.github.quant.domain.Instrument;
import io.github.quant.market.DataFeed;
import io.github.quant.market.VersionedMarketDataStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataCsvImporterTest {
    private static final String HEADER = "dataset_version,event_type,symbol,exchange,session_date,event_time,"
            + "available_time,open,high,low,close,volume,opening_quantity\n";
    private static final String OPEN = "v1,OPEN,600000,SSE,2026-01-05,2026-01-05T01:30:00Z,"
            + "2026-01-05T01:30:01Z,10.00,,,,,100\n";
    private static final String CLOSE = "v1,CLOSE,600000,SSE,2026-01-05,2026-01-05T07:00:00Z,"
            + "2026-01-05T07:01:00.123456789Z,10.00,12,9,11.25,1000,\n";
    private static final Instant AS_OF = Instant.parse("2026-01-06T00:00:00Z");

    @Test
    void importsExplicitObservationTimesAndExactAmounts() throws IOException {
        var store = new VersionedMarketDataStore("v1");
        var importer = new MarketDataCsvImporter();
        var source = new StringReader("\uFEFF" + (HEADER + CLOSE + OPEN).replace("\n", "\r\n"));
        assertEquals(2, importer.importInto(source, store));
        assertEquals(-1, source.read());

        var observations = store.snapshot(AS_OF).observations();
        assertEquals(2, observations.size());
        var opening = (DataFeed.Open) observations.stream().filter(DataFeed.Open.class::isInstance)
                .findFirst().orElseThrow();
        var closing = (DataFeed.Close) observations.stream().filter(DataFeed.Close.class::isInstance)
                .findFirst().orElseThrow();
        assertEquals(new Instrument("600000", "SSE"), opening.instrument());
        assertEquals(100, opening.availableQuantity());
        assertEquals(new BigDecimal("10.00"), opening.price());
        assertEquals(Instant.parse("2026-01-05T01:30:01Z"), opening.availableTime());
        assertEquals(Instant.parse("2026-01-05T07:01:00.123456789Z"), closing.availableTime());
        assertEquals(new BigDecimal("11.25"), closing.bar().close());
    }

    @Test
    void acceptsQuotedFieldsAndEscapedQuotes() throws IOException {
        var store = new VersionedMarketDataStore("v1");
        String csv = HEADER + OPEN.replace("600000", "\"example,\"\"name\"\"\"");
        assertEquals(1, new MarketDataCsvImporter().importInto(new StringReader(csv), store));
        assertEquals("example,\"name\"", store.snapshot(AS_OF).observations().getFirst().instrument().symbol());
    }

    @Test
    void validatesTheEntireFileBeforeAppendingAnything() {
        var importer = new MarketDataCsvImporter();
        for (String invalid : List.of(CLOSE.replace("v1,", "v2,"), CLOSE.replace(",12,", ",8,"),
                CLOSE.replace("T07:01:00.123456789Z", "T06:00:00Z"), CLOSE.replace("1000", "1e3"),
                CLOSE.replace("1000", "9223372036854775808"), CLOSE.replace(",1000,", ",1000,1"),
                CLOSE.replace("2026-01-05,", "2026-02-30,"), CLOSE.replace("600000", "\"600000"),
                CLOSE.replace("07:00:00Z", "08:00:00+01:00"), CLOSE.replace("07:00:00Z", "24:00:00Z"))) {
            var store = new VersionedMarketDataStore("v1");
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> importer.importInto(new StringReader(HEADER + OPEN + invalid), store));
            assertTrue(failure.getMessage().startsWith("CSV line 3:"), failure.getMessage());
            assertTrue(store.snapshot(AS_OF).observations().isEmpty());
        }
    }

    @Test
    void storeConflictDoesNotPartiallyApplyTheBatch() throws IOException {
        var store = new VersionedMarketDataStore("v1");
        var importer = new MarketDataCsvImporter();
        importer.importInto(new StringReader(HEADER + CLOSE), store);
        assertThrows(IllegalArgumentException.class, () -> importer.importInto(
                new StringReader(HEADER + OPEN + CLOSE.replace("11.25", "11.50")), store));
        assertEquals(1, store.snapshot(AS_OF).observations().size());
    }
}
