package io.github.quant.market.factor.query;

import io.github.quant.domain.Instrument;
import io.github.quant.market.factor.FactorStore;
import io.github.quant.market.factor.FactorStore.Definition;
import io.github.quant.market.factor.FactorStore.FactorVersion;
import io.github.quant.market.factor.FactorStore.Value;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.github.quant.market.factor.query.FactorQuery.*;
import static org.junit.jupiter.api.Assertions.*;

class FactorQueryPlannerTest {
    private static final Instant EVENT = Instant.parse("2026-01-05T07:00:00Z");
    private static final Instrument STOCK = new Instrument("600000", "SSE");
    private static final FactorVersion MOMENTUM = new FactorVersion("momentum", "1");
    private static final FactorVersion VOLUME = new FactorVersion("volume", "1");

    @Test
    void evaluatesExactDecimalAgainstOnePinnedGeneration() {
        var store = store();
        store.appendAll(MOMENTUM, List.of(new Value(STOCK, EVENT, EVENT, new BigDecimal("0.1"))));
        store.appendAll(VOLUME, List.of(new Value(STOCK, EVENT, EVENT, new BigDecimal("0.2"))));
        long pinned = store.generation();
        var query = FactorQueryPlanner.plan(new Add(new Reference(MOMENTUM), new Reference(VOLUME)));
        assertEquals(List.of(MOMENTUM, VOLUME), query.references());
        store.appendAll(MOMENTUM, List.of(new Value(STOCK, EVENT, EVENT.plusSeconds(2), new BigDecimal("0.4"))));

        assertEquals(Optional.of(new BigDecimal("0.3")), query.evaluate(store, STOCK, EVENT, EVENT.plusSeconds(1)));
        assertEquals(Optional.of(new BigDecimal("0.6")), query.evaluate(store, STOCK, EVENT, EVENT.plusSeconds(2)));
        assertEquals(Optional.of(new BigDecimal("0.3")), query.evaluate(store, STOCK, EVENT, EVENT.plusSeconds(2), pinned));
        assertEquals(Optional.empty(), query.evaluate(store, STOCK, EVENT.plusSeconds(1), EVENT.plusSeconds(2)));
    }

    @Test
    void comparesNumericallyAndOnlyEvaluatesSelectedArithmeticBranch() {
        var store = store();
        var predicate = new Compare(Comparison.EQ, literal("1.00"), literal("1"));
        var error = new Divide(literal("1"), literal("0"));
        var selected = new Subtract(new Multiply(literal("2"), literal("3")), literal("1"));

        assertEquals(Optional.of(true), FactorQueryPlanner.plan(predicate).evaluate(store, STOCK, EVENT, EVENT));
        assertEquals(Optional.of(new BigDecimal("5")), FactorQueryPlanner.plan(new When(predicate, selected, error))
                .evaluate(store, STOCK, EVENT, EVENT));
        assertThrows(ArithmeticException.class,
                () -> FactorQueryPlanner.plan(error).evaluate(store, STOCK, EVENT, EVENT));
    }

    @Test
    void distinguishesMissingValueFromAnInvisibleDefinitionOrInvalidCutoff() {
        var store = store();
        var query = FactorQueryPlanner.plan(new Compare(Comparison.GT, new Reference(MOMENTUM), literal("0")));
        assertEquals(Optional.empty(), query.evaluate(store, STOCK, EVENT, EVENT));
        assertThrows(IllegalArgumentException.class, () -> query.evaluate(store, STOCK, EVENT, EVENT, 0));
        assertThrows(IllegalArgumentException.class, () -> query.evaluate(store, STOCK, EVENT, EVENT.minusSeconds(1)));
        var unknown = new Reference(new FactorVersion(MOMENTUM.name(), "2"));
        assertThrows(IllegalArgumentException.class,
                () -> FactorQueryPlanner.plan(unknown).evaluate(store, STOCK, EVENT, EVENT));
    }

    @Test
    void rejectsOverlyDeepOrLargeExpressionsBeforeEvaluation() {
        DecimalExpression chain = literal("1");
        DecimalExpression tree = chain;
        for (int index = 0; index < 65; index++) chain = new Add(chain, literal("1"));
        for (int index = 0; index < 10; index++) tree = new Add(tree, tree);
        DecimalExpression deep = chain;
        DecimalExpression large = tree;
        assertThrows(IllegalArgumentException.class, () -> FactorQueryPlanner.plan(deep));
        assertThrows(IllegalArgumentException.class, () -> FactorQueryPlanner.plan(large));
    }

    private static FactorStore store() {
        var store = new FactorStore();
        store.register(new Definition(MOMENTUM, "Momentum", "ratio", EVENT.minusSeconds(1)));
        store.register(new Definition(VOLUME, "Volume", "ratio", EVENT.minusSeconds(1)));
        return store;
    }

    private static Literal literal(String value) {
        return new Literal(new BigDecimal(value));
    }
}
