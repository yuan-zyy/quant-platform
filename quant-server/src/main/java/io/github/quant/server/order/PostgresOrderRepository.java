package io.github.quant.server.order;

import io.github.quant.broker.spi.BrokerAdapter;
import io.github.quant.broker.spi.BrokerAdapter.Evidence;
import io.github.quant.broker.spi.BrokerAdapter.Submission;
import io.github.quant.broker.spi.BrokerAdapter.SubmissionKey;
import io.github.quant.broker.spi.BrokerAdapter.SubmissionResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** Persists one order intent per account/client key before the caller attempts submission. */
public final class PostgresOrderRepository {
    public enum Status { SUBMITTING, ACKNOWLEDGED, REJECTED, UNKNOWN }

    public record Order(Submission submission, Status status, String brokerOrderId, String code,
                        Instant createdAt, Instant updatedAt) {}

    public record Reservation(Order order, boolean created) {}

    private final DataSource dataSource;

    public PostgresOrderRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public Reservation reserve(Submission submission, Duration submissionTimeout) throws SQLException {
        long timeoutMillis = submissionTimeout.toMillis();
        if (timeoutMillis <= 0) throw new IllegalArgumentException("submission timeout must be at least 1 ms");
        try (var connection = connection()) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO broker_order
                        (account_id, client_order_id, exchange, symbol, side, quantity, limit_price, submit_deadline)
                    VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp() + (? * interval '1 millisecond'))
                    ON CONFLICT (account_id, client_order_id) DO NOTHING RETURNING *
                    """)) {
                bindKey(insert, 1, submission.key());
                insert.setString(3, submission.exchange());
                insert.setString(4, submission.symbol());
                insert.setString(5, submission.side().name());
                insert.setLong(6, submission.quantity());
                insert.setBigDecimal(7, submission.limit());
                insert.setLong(8, timeoutMillis);
                var inserted = read(insert);
                if (inserted.isPresent()) return new Reservation(inserted.get(), true);
            }
            var existing = required(get(connection, submission.key()));
            if (!sameIntent(submission, existing.submission())) {
                throw new IllegalArgumentException("client order key conflicts with the stored order intent");
            }
            return new Reservation(existing, false);
        }
    }

    public Optional<Order> get(SubmissionKey key) throws SQLException {
        try (var connection = connection()) {
            return get(connection, key);
        }
    }

    public Order complete(SubmissionKey key, SubmissionResult result) throws SQLException {
        return switch (result) {
            case BrokerAdapter.Acknowledged ack ->
                    transition(key, Status.SUBMITTING, Status.ACKNOWLEDGED, ack.brokerOrderId(), null);
            case BrokerAdapter.Rejected rejected ->
                    transition(key, Status.SUBMITTING, Status.REJECTED, null, rejected.code());
            case BrokerAdapter.Unknown ignored ->
                    transition(key, Status.SUBMITTING, Status.UNKNOWN, null, null);
        };
    }

    public Optional<Order> recoverExpired(SubmissionKey key) throws SQLException {
        try (var connection = connection();
             var update = connection.prepareStatement("""
                     UPDATE broker_order SET status = 'UNKNOWN', updated_at = clock_timestamp()
                     WHERE account_id = ? AND client_order_id = ? AND status = 'SUBMITTING'
                       AND submit_deadline <= clock_timestamp() RETURNING *
                     """)) {
            bindKey(update, 1, key);
            var recovered = read(update);
            return recovered.isPresent() ? recovered : get(connection, key);
        }
    }

    public Order applyEvidence(SubmissionKey key, Evidence evidence) throws SQLException {
        return switch (evidence) {
            case BrokerAdapter.Accepted accepted ->
                    transition(key, Status.UNKNOWN, Status.ACKNOWLEDGED, accepted.brokerOrderId(), null);
            case BrokerAdapter.Declined declined ->
                    transition(key, Status.UNKNOWN, Status.REJECTED, null, declined.code());
            // Expiry cannot fence an earlier process that has not yet reached the adapter.
            case BrokerAdapter.Absent ignored -> required(get(key));
            case BrokerAdapter.Inconclusive ignored -> required(get(key));
        };
    }

    private Order transition(SubmissionKey key, Status expected, Status next, String brokerOrderId, String code)
            throws SQLException {
        try (var connection = connection();
             var update = connection.prepareStatement("""
                     UPDATE broker_order SET status = ?, broker_order_id = ?, code = ?, updated_at = clock_timestamp()
                     WHERE account_id = ? AND client_order_id = ? AND status = ? RETURNING *
                     """)) {
            update.setString(1, next.name());
            update.setString(2, brokerOrderId);
            update.setString(3, code);
            bindKey(update, 4, key);
            update.setString(6, expected.name());
            var updated = read(update);
            return updated.isPresent() ? updated.get() : required(get(connection, key));
        }
    }

    private static Optional<Order> get(Connection connection, SubmissionKey key) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT * FROM broker_order WHERE account_id = ? AND client_order_id = ?")) {
            bindKey(query, 1, key);
            return read(query);
        }
    }

    private Connection connection() throws SQLException {
        var connection = dataSource.getConnection();
        try {
            if (!connection.getAutoCommit()) throw new SQLException("order repository requires auto-commit");
            return connection;
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void bindKey(PreparedStatement query, int offset, SubmissionKey key) throws SQLException {
        query.setString(offset, key.accountId());
        query.setString(offset + 1, key.clientOrderId());
    }

    private static Optional<Order> read(PreparedStatement query) throws SQLException {
        try (ResultSet row = query.executeQuery()) {
            if (!row.next()) return Optional.empty();
            var key = new SubmissionKey(row.getString("account_id"), row.getString("client_order_id"));
            var submission = new Submission(key, row.getString("exchange"), row.getString("symbol"),
                    BrokerAdapter.Side.valueOf(row.getString("side")), row.getLong("quantity"),
                    row.getBigDecimal("limit_price"));
            return Optional.of(new Order(submission, Status.valueOf(row.getString("status")),
                    row.getString("broker_order_id"), row.getString("code"),
                    row.getObject("created_at", OffsetDateTime.class).toInstant(),
                    row.getObject("updated_at", OffsetDateTime.class).toInstant()));
        }
    }

    private static Order required(Optional<Order> order) throws SQLException {
        return order.orElseThrow(() -> new SQLException("reserved order was not found"));
    }

    private static boolean sameIntent(Submission requested, Submission stored) {
        return requested.exchange().equals(stored.exchange()) && requested.symbol().equals(stored.symbol())
                && requested.side() == stored.side() && requested.quantity() == stored.quantity()
                && requested.limit().compareTo(stored.limit()) == 0;
    }
}
