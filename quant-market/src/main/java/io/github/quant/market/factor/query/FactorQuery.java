package io.github.quant.market.factor.query;

import io.github.quant.market.factor.FactorStore.FactorVersion;

import java.math.BigDecimal;

/** Typed expressions for a factor value or predicate at one instrument and event time. */
public final class FactorQuery {
    private FactorQuery() {}

    public sealed interface DecimalExpression permits Literal, Reference, Add, Subtract, Multiply, Divide, When {}
    public sealed interface BooleanExpression permits Compare {}

    public record Literal(BigDecimal value) implements DecimalExpression {}
    public record Reference(FactorVersion version) implements DecimalExpression {}
    public record Add(DecimalExpression left, DecimalExpression right) implements DecimalExpression {}
    public record Subtract(DecimalExpression left, DecimalExpression right) implements DecimalExpression {}
    public record Multiply(DecimalExpression left, DecimalExpression right) implements DecimalExpression {}
    public record Divide(DecimalExpression left, DecimalExpression right) implements DecimalExpression {}
    public record When(BooleanExpression condition, DecimalExpression ifTrue, DecimalExpression ifFalse)
            implements DecimalExpression {}

    public enum Comparison { LT, LE, EQ, NE, GE, GT }
    public record Compare(Comparison comparison, DecimalExpression left, DecimalExpression right)
            implements BooleanExpression {}
}
