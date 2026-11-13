package io.github.quant.risk;

import io.github.quant.broker.spi.BrokerAdapter.Submission;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable per-order quantity and limit-price notional policy. */
public final class PreTradeRiskPolicy {
    public record InstrumentKey(String exchange, String symbol) {
        public InstrumentKey {
            requireText(exchange, "exchange");
            requireText(symbol, "symbol");
        }
    }

    public record Limits(long maxQuantity, BigDecimal maxNotional) {
        public Limits {
            Objects.requireNonNull(maxNotional);
            if (maxQuantity <= 0 || maxNotional.signum() <= 0) {
                throw new IllegalArgumentException("risk limits must be positive");
            }
        }
    }

    public enum Decision {
        ALLOWED,
        ACCOUNT_NOT_CONFIGURED,
        INSTRUMENT_NOT_CONFIGURED,
        QUANTITY_LIMIT_EXCEEDED,
        NOTIONAL_LIMIT_EXCEEDED
    }

    private final Map<String, Map<InstrumentKey, Limits>> accounts;

    public PreTradeRiskPolicy(Map<String, Map<InstrumentKey, Limits>> accountLimits) {
        var copy = new HashMap<String, Map<InstrumentKey, Limits>>();
        accountLimits.forEach((account, limits) -> {
            requireText(account, "account ID");
            copy.put(account, Map.copyOf(limits));
        });
        accounts = Map.copyOf(copy);
    }

    public Decision evaluate(Submission request) {
        var account = accounts.get(request.key().accountId());
        if (account == null) return Decision.ACCOUNT_NOT_CONFIGURED;
        var limits = account.get(new InstrumentKey(request.exchange(), request.symbol()));
        if (limits == null) return Decision.INSTRUMENT_NOT_CONFIGURED;
        if (request.quantity() > limits.maxQuantity()) return Decision.QUANTITY_LIMIT_EXCEEDED;
        var notional = request.limit().multiply(BigDecimal.valueOf(request.quantity()));
        if (notional.compareTo(limits.maxNotional()) > 0) return Decision.NOTIONAL_LIMIT_EXCEEDED;
        return Decision.ALLOWED;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
