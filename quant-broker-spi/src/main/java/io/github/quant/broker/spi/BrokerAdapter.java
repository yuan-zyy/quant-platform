package io.github.quant.broker.spi;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Order-submission boundary for a configured adapter and authorized account.
 * The SPI supplies no external transport or broker implementation.
 */
public interface BrokerAdapter {
    /**
     * Attempts one submission. The key must correlate with reconciliation for the same account.
     * A transport failure or interruption can leave the outcome unknown.
     */
    SubmissionResult submit(Submission request) throws IOException, InterruptedException;

    /** Queries evidence for an earlier submission without creating or modifying any order. */
    Evidence reconcile(SubmissionKey key) throws IOException, InterruptedException;

    record SubmissionKey(String accountId, String clientOrderId) {
        public SubmissionKey {
            requireText(accountId, "account ID");
            requireText(clientOrderId, "client order ID");
        }
    }

    enum Side { BUY, SELL }

    record Submission(SubmissionKey key, String exchange, String symbol, Side side, long quantity, BigDecimal limit) {
        public Submission {
            Objects.requireNonNull(key);
            Objects.requireNonNull(side);
            requireText(exchange, "exchange");
            requireText(symbol, "symbol");
            if (quantity <= 0 || Objects.requireNonNull(limit).signum() <= 0) {
                throw new IllegalArgumentException("quantity and limit must be positive");
            }
        }
    }

    sealed interface SubmissionResult permits Acknowledged, Rejected, Unknown {}

    /** The broker accepted the order and assigned an identifier. */
    record Acknowledged(String brokerOrderId) implements SubmissionResult {
        public Acknowledged {
            requireText(brokerOrderId, "broker order ID");
        }
    }

    /** Final rejection of this submission, with no accepted order for the key. */
    record Rejected(String code) implements SubmissionResult {
        public Rejected {
            requireText(code, "rejection code");
        }
    }

    /** The submission might have produced an order. */
    record Unknown() implements SubmissionResult {}

    sealed interface Evidence permits Accepted, Declined, Absent, Inconclusive {}

    record Accepted(String brokerOrderId) implements Evidence {
        public Accepted {
            requireText(brokerOrderId, "broker order ID");
        }
    }

    record Declined(String code) implements Evidence {
        public Declined {
            requireText(code, "rejection code");
        }
    }

    /** Authoritative final absence. The original attempt created no order and cannot complete later. */
    record Absent() implements Evidence {}

    /** The original outcome is still uncertain, including a lookup with incomplete coverage. */
    record Inconclusive() implements Evidence {}

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
