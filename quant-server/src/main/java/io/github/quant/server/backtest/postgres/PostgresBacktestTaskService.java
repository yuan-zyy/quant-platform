package io.github.quant.server.backtest.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.quant.server.backtest.BacktestTaskScheduler;
import io.github.quant.server.backtest.BacktestTaskService;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Resolves authorized scenarios and exposes durable task summaries. */
public final class PostgresBacktestTaskService implements BacktestTaskService {
    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface
    public interface ScenarioVersionResolver {
        Optional<String> resolve(String tenantId, String scenarioId);
    }

    private record StoredResult(int schemaVersion, StoredMetrics metrics) {}

    private record StoredMetrics(String endingEquity, String totalReturn, String maxDrawdown, int fillCount) {}

    private final PostgresBacktestRepository repository;
    private final ScenarioVersionResolver versions;

    public PostgresBacktestTaskService(PostgresBacktestRepository repository, ScenarioVersionResolver versions) {
        this.repository = Objects.requireNonNull(repository);
        this.versions = Objects.requireNonNull(versions);
    }

    @Override
    public Optional<Task> submit(String tenantId, String scenarioId, String idempotencyKey) throws SQLException {
        if (idempotencyKey != null) {
            var existing = repository.getByKey(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                if (!existing.get().scenarioId().equals(scenarioId)) {
                    throw new BacktestTaskService.IdempotencyConflictException();
                }
                return Optional.of(task(existing.get()));
            }
        }
        var version = versions.resolve(tenantId, scenarioId);
        if (version.isEmpty()) return Optional.empty();
        try {
            return Optional.of(task(repository.submit(tenantId, idempotencyKey, scenarioId, version.get())));
        } catch (PostgresBacktestRepository.IdempotencyConflictException conflict) {
            throw new BacktestTaskService.IdempotencyConflictException();
        }
    }

    @Override
    public Optional<Task> get(String tenantId, UUID taskId) throws SQLException {
        var task = repository.get(tenantId, taskId);
        return task.isPresent() ? Optional.of(task(task.get())) : Optional.empty();
    }

    @Override
    public Optional<Task> cancel(String tenantId, UUID taskId) throws SQLException {
        var task = repository.cancel(tenantId, taskId);
        return task.isPresent() ? Optional.of(task(task.get())) : Optional.empty();
    }

    static String encode(Result result) throws SQLException {
        try {
            return JSON.writeValueAsString(new StoredResult(1, new StoredMetrics(result.endingEquity().toString(),
                    result.totalReturn().toString(), result.maxDrawdown().toString(), result.fillCount())));
        } catch (JsonProcessingException failure) {
            throw new SQLException("could not encode backtest result", failure);
        }
    }

    private static Task task(PostgresBacktestRepository.Task row) throws SQLException {
        Result result = null;
        if (row.resultJson() != null) {
            try {
                var decoded = JSON.readValue(row.resultJson(), StoredResult.class);
                if (decoded == null || decoded.schemaVersion() != 1 || decoded.metrics() == null
                        || decoded.metrics().endingEquity() == null || decoded.metrics().totalReturn() == null
                        || decoded.metrics().maxDrawdown() == null || decoded.metrics().fillCount() < 0) {
                    throw new SQLException("invalid stored backtest result");
                }
                var metrics = decoded.metrics();
                result = new Result(new BigDecimal(metrics.endingEquity()), new BigDecimal(metrics.totalReturn()),
                        new BigDecimal(metrics.maxDrawdown()), metrics.fillCount());
            } catch (JsonProcessingException | NumberFormatException failure) {
                throw new SQLException("could not decode backtest result", failure);
            }
        }
        return new Task(row.id(), BacktestTaskScheduler.Status.valueOf(row.status().name()),
                row.submittedAt(), row.startedAt(), row.finishedAt(), result);
    }
}
