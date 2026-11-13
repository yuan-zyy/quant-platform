package io.github.quant.market;

import io.github.quant.domain.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionedMarketDataStoreTest {
    private static final Instrument STOCK = new Instrument("600000", "SSE");
    private static final LocalDate DATE = LocalDate.of(2026, 1, 5);
    private static final Instant OPEN_TIME = Instant.parse("2026-01-05T01:30:00Z");
    private static final Instant CLOSE_TIME = Instant.parse("2026-01-05T07:00:00Z");
    private static final Instant FIRST_CLOSE = CLOSE_TIME.plusSeconds(30);
    private static final Instant CORRECTION = CLOSE_TIME.plusSeconds(60);
    private static final DataFeed.Open OPENING =
            new DataFeed.Open(STOCK, DATE, OPEN_TIME, OPEN_TIME, BigDecimal.TEN, 100);

    @Test
    void selectsOnlyVisiblePublicationsAndUsesFirstPublicationForReplay() {
        var store = new VersionedMarketDataStore("sample-v1");
        var original = close(FIRST_CLOSE, "10");
        var corrected = close(CORRECTION, "11");
        store.appendAll(List.of(corrected, OPENING, original));

        assertEquals(List.of(), store.snapshot(OPEN_TIME.minusNanos(1)).observations());
        assertEquals(List.of(OPENING), store.snapshot(FIRST_CLOSE.minusNanos(1)).observations());
        assertEquals(List.of(OPENING, original), store.snapshot(FIRST_CLOSE).observations());
        var snapshot = store.snapshot(CORRECTION);
        assertEquals(List.of(OPENING, corrected), snapshot.observations());
        assertEquals(List.of(OPENING, original), replay(snapshot));
        assertEquals("sample-v1", snapshot.datasetVersion());
        assertEquals(CORRECTION, snapshot.asOf());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.observations().clear());
    }

    @Test
    void pinnedGenerationSurvivesBackdatedImport() {
        var store = new VersionedMarketDataStore("sample-v1");
        var original = close(FIRST_CLOSE, "10");
        var later = close(CORRECTION, "11");
        store.appendAll(List.of(later));
        var pinned = store.snapshot(CORRECTION);
        store.appendAll(List.of(original));

        assertEquals(1, pinned.generation());
        assertEquals(2, store.snapshot(CORRECTION).generation());
        assertEquals(List.of(later), replay(pinned));
        assertEquals(replay(pinned), replay(store.snapshot(CORRECTION, pinned.generation())));
        assertEquals(List.of(original), replay(store.snapshot(CORRECTION)));
        assertEquals(List.of(), store.snapshot(CORRECTION, 0).observations());
        assertThrows(IllegalArgumentException.class, () -> store.snapshot(CORRECTION, 3));
    }

    @Test
    void rejectsConflictingBatchesAtomicallyAndKeepsIdenticalRetriesIdempotent() {
        var store = new VersionedMarketDataStore("sample-v1");
        var original = close(FIRST_CLOSE, "10");
        store.appendAll(List.of(original, original));
        store.appendAll(List.of(original));
        assertEquals(1, store.snapshot(CORRECTION).generation());

        assertThrows(IllegalArgumentException.class,
                () -> store.appendAll(List.of(OPENING, close(FIRST_CLOSE, "11"))));
        assertEquals(1, store.snapshot(CORRECTION).generation());
        assertEquals(List.of(original), store.snapshot(CORRECTION).observations());
        var changedEventTime = new DataFeed.Close(STOCK, CLOSE_TIME.plusSeconds(1), CORRECTION, original.bar());
        assertThrows(IllegalArgumentException.class, () -> store.appendAll(List.of(changedEventTime)));
    }

    private static List<DataFeed.Observation> replay(MarketDataSnapshot snapshot) {
        var observations = new ArrayList<DataFeed.Observation>();
        snapshot.replayFeed().replay(observations::add);
        return observations;
    }

    private static DataFeed.Close close(Instant publication, String price) {
        var bar = new Bar(DATE, BigDecimal.TEN, new BigDecimal("11"), BigDecimal.TEN,
                new BigDecimal(price), 1000);
        return new DataFeed.Close(STOCK, CLOSE_TIME, publication, bar);
    }
}
