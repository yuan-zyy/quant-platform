package io.github.quant.market.factor.query;

import io.github.quant.domain.Instrument;
import io.github.quant.market.factor.FactorSnapshotReader;
import io.github.quant.market.factor.FactorStore;
import io.github.quant.market.factor.FactorStore.FactorVersion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.github.quant.market.factor.query.FactorQuery.*;

/**
 * Compiles an expression supplied by trusted Java code and resolves all references
 * against one factor journal generation. Decimal operations are exact except for division,
 * which rounds using DECIMAL128. Expression size limits bound traversal, not decimal precision.
 */
public final class FactorQueryPlanner {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 1024;

    private FactorQueryPlanner() {}

    public static Plan<BigDecimal> plan(DecimalExpression expression) {
        var compiler = new Compiler();
        return new Plan<>(compiler.decimal(expression, 1), List.copyOf(compiler.versions));
    }

    public static Plan<Boolean> plan(BooleanExpression expression) {
        var compiler = new Compiler();
        return new Plan<>(compiler.predicate(expression, 1), List.copyOf(compiler.versions));
    }

    public static final class Plan<T> {
        private final Function<Map<FactorVersion, Optional<BigDecimal>>, Optional<T>> expression;
        private final List<FactorVersion> versions;

        private Plan(Function<Map<FactorVersion, Optional<BigDecimal>>, Optional<T>> expression,
                     List<FactorVersion> versions) {
            this.expression = expression;
            this.versions = versions;
        }

        public List<FactorVersion> references() {
            return versions;
        }

        public Optional<T> evaluate(FactorStore store, Instrument instrument, Instant eventTime, Instant asOf) {
            return evaluate((FactorSnapshotReader) store, instrument, eventTime, asOf);
        }

        public Optional<T> evaluate(FactorStore store, Instrument instrument, Instant eventTime,
                                    Instant asOf, long generation) {
            return evaluate((FactorSnapshotReader) store, instrument, eventTime, asOf, generation);
        }

        public Optional<T> evaluate(FactorSnapshotReader reader, Instrument instrument, Instant eventTime,
                                    Instant asOf) {
            long generation = reader.generation();
            return evaluateAt(reader, instrument, eventTime, asOf, generation, generation);
        }

        public Optional<T> evaluate(FactorSnapshotReader reader, Instrument instrument, Instant eventTime,
                                    Instant asOf, long generation) {
            return evaluateAt(reader, instrument, eventTime, asOf, generation, reader.generation());
        }

        private Optional<T> evaluateAt(FactorSnapshotReader reader, Instrument instrument, Instant eventTime,
                                        Instant asOf, long generation, long latestGeneration) {
            Objects.requireNonNull(instrument);
            Objects.requireNonNull(eventTime);
            Objects.requireNonNull(asOf);
            if (eventTime.isAfter(asOf)) throw new IllegalArgumentException("factor event time exceeds cutoff");
            if (generation < 0 || generation > latestGeneration) {
                throw new IllegalArgumentException("unknown factor generation");
            }
            var values = new LinkedHashMap<FactorVersion, Optional<BigDecimal>>();
            for (var version : versions) {
                var snapshot = reader.snapshot(version, asOf, generation);
                var value = snapshot.values().stream()
                        .filter(row -> row.instrument().equals(instrument) && row.eventTime().equals(eventTime))
                        .map(FactorStore.Value::value)
                        .findFirst();
                values.put(version, value);
            }
            return expression.apply(values);
        }
    }

    private static final class Compiler {
        private final LinkedHashSet<FactorVersion> versions = new LinkedHashSet<>();
        private int nodes;

        private Function<Map<FactorVersion, Optional<BigDecimal>>, Optional<BigDecimal>> decimal(
                DecimalExpression expression, int depth) {
            visit(depth);
            int child = depth + 1;
            return switch (expression) {
                case Literal literal -> {
                    var value = Optional.of(literal.value());
                    yield ignored -> value;
                }
                case Reference reference -> {
                    var version = Objects.requireNonNull(reference.version());
                    versions.add(version);
                    yield values -> values.get(version);
                }
                case Add add -> combine(add.left(), add.right(), child, BigDecimal::add);
                case Subtract subtract -> combine(subtract.left(), subtract.right(), child, BigDecimal::subtract);
                case Multiply multiply -> combine(multiply.left(), multiply.right(), child, BigDecimal::multiply);
                case Divide divide -> combine(divide.left(), divide.right(), child,
                        (left, right) -> left.divide(right, MathContext.DECIMAL128));
                case When when -> {
                    var condition = predicate(when.condition(), child);
                    var ifTrue = decimal(when.ifTrue(), child);
                    var ifFalse = decimal(when.ifFalse(), child);
                    yield values -> condition.apply(values)
                            .flatMap(selected -> (selected ? ifTrue : ifFalse).apply(values));
                }
            };
        }

        private Function<Map<FactorVersion, Optional<BigDecimal>>, Optional<Boolean>> predicate(
                BooleanExpression expression, int depth) {
            visit(depth);
            return switch (expression) {
                case Compare compare -> {
                    var comparison = Objects.requireNonNull(compare.comparison());
                    yield combine(compare.left(), compare.right(), depth + 1,
                            (left, right) -> matches(comparison, left.compareTo(right)));
                }
            };
        }

        private <T> Function<Map<FactorVersion, Optional<BigDecimal>>, Optional<T>> combine(
                DecimalExpression left, DecimalExpression right, int depth, BiFunction<BigDecimal, BigDecimal, T> operation) {
            var first = decimal(left, depth);
            var second = decimal(right, depth);
            return values -> first.apply(values).flatMap(a -> second.apply(values).map(b -> operation.apply(a, b)));
        }

        private void visit(int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw new IllegalArgumentException("factor expression is too large");
            }
        }
    }

    private static boolean matches(Comparison comparison, int order) {
        return switch (comparison) {
            case LT -> order < 0;
            case LE -> order <= 0;
            case EQ -> order == 0;
            case NE -> order != 0;
            case GE -> order >= 0;
            case GT -> order > 0;
        };
    }
}
