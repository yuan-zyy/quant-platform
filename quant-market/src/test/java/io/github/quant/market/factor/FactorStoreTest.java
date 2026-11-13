package io.github.quant.market.factor;

import io.github.quant.domain.Instrument;
import io.github.quant.market.factor.FactorStore.Definition;
import io.github.quant.market.factor.FactorStore.FactorVersion;
import io.github.quant.market.factor.FactorStore.Value;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactorStoreTest {
    private static final FactorVersion VERSION = new FactorVersion("momentum", "1");
    private static final Instrument STOCK = new Instrument("600000", "SSE");
    private static final Instant EVENT = Instant.parse("2026-01-05T07:00:00Z");
    private static final Instant PUBLISHED = EVENT.plusSeconds(60);
    private static final Instant CORRECTED = EVENT.plusSeconds(120);
    private static final Definition DEFINITION = new Definition(VERSION, "Daily momentum", "ratio", EVENT);

    @Test
    void selectsOnlyPublishedValuesAndPinsBackdatedCorrections() {
        var store = new FactorStore();
        var original = value(PUBLISHED, "0.012300");
        var correction = value(CORRECTED, "-0.012301");
        store.register(DEFINITION);
        store.appendAll(VERSION, List.of(original));
        var pinned = store.snapshot(VERSION, CORRECTED);
        store.appendAll(VERSION, List.of(correction));

        assertEquals(DEFINITION, pinned.definition());
        assertEquals(List.of(), store.snapshot(VERSION, EVENT).values());
        assertEquals(List.of(original), store.snapshot(VERSION, PUBLISHED).values());
        assertEquals(List.of(correction), store.snapshot(VERSION, CORRECTED).values());
        assertEquals(pinned, store.snapshot(VERSION, CORRECTED, pinned.generation()));
    }

    @Test
    void appliesDefinitionVisibilityAndSeparatesVersions() {
        var store = new FactorStore();
        var v2 = new FactorVersion("momentum", "2");
        store.register(DEFINITION);
        store.appendAll(VERSION, List.of(value(PUBLISHED, "0.10")));
        var pinned = store.snapshot(VERSION, CORRECTED);
        store.register(new Definition(v2, "Revised momentum", "ratio", CORRECTED));
        store.appendAll(v2, List.of(value(CORRECTED, "0.20")));

        assertThrows(IllegalArgumentException.class, () -> store.snapshot(v2, PUBLISHED));
        assertThrows(IllegalArgumentException.class, () -> store.snapshot(v2, CORRECTED, pinned.generation()));
        assertThrows(IllegalArgumentException.class, () -> store.appendAll(v2, List.of(value(PUBLISHED, "0.30"))));
        assertEquals(List.of(value(PUBLISHED, "0.10")), store.snapshot(VERSION, CORRECTED).values());
        assertEquals(List.of(value(CORRECTED, "0.20")), store.snapshot(v2, CORRECTED).values());
    }

    @Test
    void rejectsConflictsWithoutCommittingOtherRowsAndAcceptsExactRetries() {
        var store = new FactorStore();
        var original = value(PUBLISHED, "0.10");
        store.register(DEFINITION);
        store.appendAll(VERSION, List.of(original));
        var snapshot = store.snapshot(VERSION, CORRECTED);

        store.register(DEFINITION);
        store.appendAll(VERSION, List.of(original, original));
        assertThrows(IllegalArgumentException.class,
                () -> store.appendAll(VERSION, List.of(value(CORRECTED, "0.20"), value(PUBLISHED, "0.1"))));
        assertThrows(IllegalArgumentException.class,
                () -> store.register(new Definition(VERSION, "Different description", "ratio", EVENT)));
        assertThrows(IllegalArgumentException.class, () -> value(EVENT.minusSeconds(1), "0.00"));
        assertEquals(snapshot, store.snapshot(VERSION, CORRECTED));
    }

    private static Value value(Instant publication, String amount) {
        return new Value(STOCK, EVENT, publication, new BigDecimal(amount));
    }
}
