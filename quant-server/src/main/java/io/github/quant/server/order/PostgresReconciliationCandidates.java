package io.github.quant.server.order;

import io.github.quant.broker.spi.BrokerAdapter.SubmissionKey;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Claims a bounded batch and advances its next lookup time in one PostgreSQL statement. */
public final class PostgresReconciliationCandidates {
    private static final int MAX_BATCH_SIZE = 1_000;
    private final DataSource dataSource;

    public PostgresReconciliationCandidates(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    /**
     * Applies the minimum age after the last UNKNOWN transition or after the submission deadline.
     * A crashed worker leaves the order eligible again when the retry delay expires.
     */
    public List<SubmissionKey> claimReconciliationCandidates(int limit, Duration minimumAge, Duration retryDelay)
            throws SQLException {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("reconciliation batch must contain 1 to 1000 orders");
        }
        long minimumAgeMillis = milliseconds(minimumAge, true);
        long retryDelayMillis = milliseconds(retryDelay, false);
        try (var connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) throw new SQLException("reconciliation claims require auto-commit");
            try (var statement = connection.prepareStatement("""
                    WITH due AS (
                        SELECT account_id, client_order_id, next_reconcile_at, updated_at
                        FROM broker_order
                        WHERE status IN ('UNKNOWN', 'SUBMITTING')
                          AND next_reconcile_at <= statement_timestamp()
                          AND CASE WHEN status = 'UNKNOWN' THEN updated_at ELSE submit_deadline END
                                <= statement_timestamp() - (? * interval '1 millisecond')
                        ORDER BY next_reconcile_at, updated_at, account_id, client_order_id
                        LIMIT ? FOR UPDATE SKIP LOCKED
                    ), claimed AS (
                        UPDATE broker_order AS orders
                        SET next_reconcile_at = clock_timestamp() + (? * interval '1 millisecond')
                        FROM due
                        WHERE orders.account_id = due.account_id AND orders.client_order_id = due.client_order_id
                        RETURNING orders.account_id, orders.client_order_id, due.next_reconcile_at, due.updated_at
                    )
                    SELECT account_id, client_order_id FROM claimed
                    ORDER BY next_reconcile_at, updated_at, account_id, client_order_id
                    """)) {
                statement.setLong(1, minimumAgeMillis);
                statement.setInt(2, limit);
                statement.setLong(3, retryDelayMillis);
                try (var rows = statement.executeQuery()) {
                    var claimed = new ArrayList<SubmissionKey>();
                    while (rows.next()) {
                        claimed.add(new SubmissionKey(rows.getString("account_id"), rows.getString("client_order_id")));
                    }
                    return List.copyOf(claimed);
                }
            }
        }
    }

    private static long milliseconds(Duration duration, boolean allowZero) {
        long millis = duration.toMillis();
        if (duration.isNegative() || (!allowZero && millis == 0)) {
            throw new IllegalArgumentException("invalid reconciliation duration");
        }
        return millis;
    }
}
