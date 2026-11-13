package io.github.quant.server.backtest.postgres;

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
import java.util.UUID;

/** PostgreSQL task transitions. Each mutating statement commits independently. */
public final class PostgresBacktestRepository {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELED }

    public record Task(UUID id, String tenantId, String scenarioId, String scenarioVersion, Status status,
                       Instant submittedAt, Instant startedAt, Instant finishedAt, String resultJson,
                       String failureCode, int attemptCount) {}

    public record Lease(Task task, String owner, long epoch, Instant expiresAt) {}

    public static final class IdempotencyConflictException extends SQLException {
        public IdempotencyConflictException() {
            super("idempotency key was already used for a different scenario");
        }
    }

    private static final String LEASE_CONDITION = """
             WHERE tenant_id = ? AND task_id = ? AND lease_owner = ? AND lease_epoch = ?
               AND status = 'RUNNING' AND lease_expires_at > clock_timestamp()
            """;

    private final DataSource dataSource;

    public PostgresBacktestRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public Task submit(String tenantId, String key, String scenarioId, String scenarioVersion) throws SQLException {
        requireText(tenantId, "tenant");
        requireText(scenarioId, "scenario");
        requireText(scenarioVersion, "scenario version");
        if (key != null) requireText(key, "idempotency key");
        try (var connection = connection()) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO backtest_task (tenant_id, task_id, idempotency_key, scenario_id, scenario_version)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, idempotency_key) DO NOTHING RETURNING *
                    """)) {
                insert.setString(1, tenantId);
                insert.setObject(2, UUID.randomUUID());
                insert.setString(3, key);
                insert.setString(4, scenarioId);
                insert.setString(5, scenarioVersion);
                var inserted = queryTask(insert);
                if (inserted.isPresent()) return inserted.get();
            }
            // A separate statement sees the concurrent winning row after INSERT's unique-key wait.
            try (var query = connection.prepareStatement("""
                    SELECT * FROM backtest_task WHERE tenant_id = ? AND idempotency_key = ?
                    """)) {
                query.setString(1, tenantId);
                query.setString(2, key);
                Task existing = queryTask(query).orElseThrow(() -> new SQLException("idempotent task was not found"));
                if (!existing.scenarioId().equals(scenarioId)) throw new IdempotencyConflictException();
                return existing;
            }
        }
    }

    public Optional<Task> get(String tenantId, UUID id) throws SQLException {
        try (var connection = connection();
             var query = connection.prepareStatement("""
                     SELECT * FROM backtest_task WHERE tenant_id = ? AND task_id = ?
                     """)) {
            bindTask(query, 1, tenantId, id);
            return queryTask(query);
        }
    }

    public Optional<Task> getByKey(String tenantId, String key) throws SQLException {
        requireText(tenantId, "tenant");
        requireText(key, "idempotency key");
        try (var connection = connection();
             var query = connection.prepareStatement("""
                     SELECT * FROM backtest_task WHERE tenant_id = ? AND idempotency_key = ?
                     """)) {
            query.setString(1, tenantId);
            query.setString(2, key);
            return queryTask(query);
        }
    }

    public Optional<Task> cancel(String tenantId, UUID id) throws SQLException {
        try (var connection = connection();
             var update = connection.prepareStatement("""
                     UPDATE backtest_task SET status = 'CANCELED', finished_at = clock_timestamp(),
                         lease_owner = NULL, lease_expires_at = NULL
                     WHERE tenant_id = ? AND task_id = ? AND status IN ('QUEUED','RUNNING')
                     RETURNING *
                     """)) {
            bindTask(update, 1, tenantId, id);
            var canceled = queryTask(update);
            if (canceled.isPresent()) return canceled;
        }
        return get(tenantId, id);
    }

    public Optional<Lease> claim(String owner, Duration lease, int maxAttempts) throws SQLException {
        requireText(owner, "lease owner");
        long millis = milliseconds(lease, false);
        if (maxAttempts < 1) throw new IllegalArgumentException("attempt limit must be positive");
        try (var connection = connection();
             var update = connection.prepareStatement("""
                     WITH candidate AS (
                         SELECT tenant_id, task_id FROM backtest_task
                         WHERE status = 'QUEUED' AND next_attempt_at <= clock_timestamp() AND attempt_count < ?
                         ORDER BY next_attempt_at, submitted_at, task_id
                         LIMIT 1 FOR UPDATE SKIP LOCKED
                     )
                     UPDATE backtest_task AS task SET status = 'RUNNING',
                         started_at = coalesce(started_at, clock_timestamp()), lease_owner = ?,
                         lease_epoch = lease_epoch + 1, attempt_count = attempt_count + 1,
                         lease_expires_at = clock_timestamp() + (? * interval '1 millisecond')
                     FROM candidate WHERE task.tenant_id = candidate.tenant_id AND task.task_id = candidate.task_id
                     RETURNING task.*
                     """)) {
            update.setInt(1, maxAttempts);
            update.setString(2, owner);
            update.setLong(3, millis);
            try (var rows = update.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new Lease(task(rows), owner, rows.getLong("lease_epoch"),
                        instant(rows, "lease_expires_at")));
            }
        }
    }

    public boolean renew(Lease lease, Duration duration) throws SQLException {
        try (var connection = connection();
             var update = connection.prepareStatement("""
                     UPDATE backtest_task SET lease_expires_at = clock_timestamp() + (? * interval '1 millisecond')
                     """ + LEASE_CONDITION)) {
            update.setLong(1, milliseconds(duration, false));
            bindLease(update, 2, lease);
            return update.executeUpdate() == 1;
        }
    }

    public boolean succeed(Lease lease, String resultJson) throws SQLException {
        requireText(resultJson, "result JSON");
        return finish(lease, Status.SUCCEEDED, resultJson, null);
    }

    public boolean fail(Lease lease, String failureCode) throws SQLException {
        requireText(failureCode, "failure code");
        return finish(lease, Status.FAILED, null, failureCode);
    }

    private boolean finish(Lease lease, Status status, String resultJson, String failureCode) throws SQLException {
        try (var connection = connection();
             var update = connection.prepareStatement("""
                     UPDATE backtest_task SET status = ?, result_json = CAST(? AS jsonb), failure_code = ?,
                         finished_at = clock_timestamp(), lease_owner = NULL, lease_expires_at = NULL
                     """ + LEASE_CONDITION)) {
            update.setString(1, status.name());
            update.setString(2, resultJson);
            update.setString(3, failureCode);
            bindLease(update, 4, lease);
            return update.executeUpdate() == 1;
        }
    }

    public int recoverExpired(int maxAttempts, Duration backoff, int batchSize) throws SQLException {
        if (maxAttempts < 1 || batchSize < 1) throw new IllegalArgumentException("recovery limits must be positive");
        long millis = milliseconds(backoff, true);
        try (var connection = connection();
             var update = connection.prepareStatement("""
                     WITH expired AS (
                         SELECT tenant_id, task_id, attempt_count >= ? AS exhausted FROM backtest_task
                         WHERE status = 'RUNNING' AND lease_expires_at <= clock_timestamp()
                         ORDER BY lease_expires_at, task_id LIMIT ? FOR UPDATE SKIP LOCKED
                     )
                     UPDATE backtest_task AS task SET status = CASE WHEN exhausted THEN 'FAILED' ELSE 'QUEUED' END,
                         failure_code = CASE WHEN exhausted THEN 'lease_expired' END,
                         finished_at = CASE WHEN exhausted THEN clock_timestamp() END,
                         next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                         lease_owner = NULL, lease_expires_at = NULL
                     FROM expired WHERE task.tenant_id = expired.tenant_id AND task.task_id = expired.task_id
                     """)) {
            update.setInt(1, maxAttempts);
            update.setInt(2, batchSize);
            update.setLong(3, millis);
            return update.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        var connection = dataSource.getConnection();
        try {
            if (!connection.getAutoCommit()) throw new SQLException("backtest task repository requires auto-commit");
            return connection;
        } catch (SQLException invalid) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                invalid.addSuppressed(closeFailure);
            }
            throw invalid;
        }
    }

    private static void bindTask(PreparedStatement statement, int offset, String tenant, UUID id) throws SQLException {
        requireText(tenant, "tenant");
        statement.setString(offset, tenant);
        statement.setObject(offset + 1, Objects.requireNonNull(id));
    }

    private static void bindLease(PreparedStatement statement, int offset, Lease lease) throws SQLException {
        bindTask(statement, offset, lease.task().tenantId(), lease.task().id());
        statement.setString(offset + 2, lease.owner());
        statement.setLong(offset + 3, lease.epoch());
    }

    private static Optional<Task> queryTask(PreparedStatement query) throws SQLException {
        try (var rows = query.executeQuery()) {
            return rows.next() ? Optional.of(task(rows)) : Optional.empty();
        }
    }

    private static Task task(ResultSet row) throws SQLException {
        return new Task(row.getObject("task_id", UUID.class), row.getString("tenant_id"),
                row.getString("scenario_id"), row.getString("scenario_version"),
                Status.valueOf(row.getString("status")), instant(row, "submitted_at"), instant(row, "started_at"),
                instant(row, "finished_at"), row.getString("result_json"), row.getString("failure_code"),
                row.getInt("attempt_count"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static long milliseconds(Duration value, boolean allowZero) {
        long millis = value.toMillis();
        if (value.isNegative() || (!allowZero && millis == 0)) {
            throw new IllegalArgumentException("invalid task duration");
        }
        return millis;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
