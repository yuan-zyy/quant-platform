package io.github.quant.server.backtest;

import io.github.quant.backtest.PerformanceMetrics;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped task operations used by the HTTP adapter. */
public interface BacktestTaskService {
    record Result(BigDecimal endingEquity, BigDecimal totalReturn, BigDecimal maxDrawdown, int fillCount) {
        public static Result from(PerformanceMetrics metrics) {
            return new Result(metrics.endingEquity(), metrics.totalReturn(), metrics.maxDrawdown(), metrics.fillCount());
        }
    }

    record Task(UUID id, BacktestTaskScheduler.Status status, Instant submittedAt, Instant startedAt,
                Instant finishedAt, Result result) {
        public static Task from(BacktestTaskScheduler.Snapshot snapshot) {
            return new Task(snapshot.id(), snapshot.status(), snapshot.submittedAt(), snapshot.startedAt(),
                    snapshot.finishedAt(), snapshot.result() == null ? null : Result.from(snapshot.result().metrics()));
        }
    }

    final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() {
            super("Idempotency key conflicts with an existing submission");
        }
    }

    Optional<Task> submit(String tenantId, String scenarioId, String idempotencyKey) throws SQLException;

    Optional<Task> get(String tenantId, UUID taskId) throws SQLException;

    Optional<Task> cancel(String tenantId, UUID taskId) throws SQLException;
}
