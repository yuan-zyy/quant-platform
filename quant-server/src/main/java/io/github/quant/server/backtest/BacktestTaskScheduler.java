package io.github.quant.server.backtest;

import io.github.quant.backtest.BacktestEngine;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded in-memory scheduler. Tenant IDs must come from the trusted caller context. */
public final class BacktestTaskScheduler implements AutoCloseable {
    @FunctionalInterface
    public interface BacktestJob {
        BacktestEngine.ReplayResult run() throws Exception;
    }

    public enum Status {
        QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELED;

        private boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELED;
        }
    }

    public record Snapshot(UUID id, Status status, Instant submittedAt, Instant startedAt,
                           Instant finishedAt, BacktestEngine.ReplayResult result) {}

    private static final class Task {
        private final UUID id = UUID.randomUUID();
        private final Instant submittedAt = Instant.now();
        private final String tenantId;
        private Status status = Status.QUEUED;
        private Instant startedAt;
        private Instant finishedAt;
        private BacktestEngine.ReplayResult result;
        private FutureTask<Void> execution;

        private Task(String tenantId) {
            this.tenantId = tenantId;
        }

        private Snapshot snapshot() {
            return new Snapshot(id, status, submittedAt, startedAt, finishedAt, result);
        }
    }

    private final ThreadPoolExecutor executor;
    private final int historyCapacity;
    private final LinkedHashMap<UUID, Task> tasks = new LinkedHashMap<>();

    public BacktestTaskScheduler(int workers, int queueCapacity, int historyCapacity) {
        if (workers < 1 || queueCapacity < 1 || historyCapacity < 1) {
            throw new IllegalArgumentException("scheduler capacities must be positive");
        }
        this.historyCapacity = historyCapacity;
        executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Thread.ofPlatform().name("backtest-task-", 0).factory());
    }

    public synchronized Snapshot submit(String tenantId, BacktestJob job) {
        requireTenant(tenantId);
        Objects.requireNonNull(job);
        Task evicted = null;
        if (tasks.size() == historyCapacity) {
            evicted = tasks.values().stream().filter(task -> task.status.terminal()).findFirst()
                    .orElseThrow(() -> new RejectedExecutionException("backtest history is full of active tasks"));
        }
        var task = new Task(tenantId);
        task.execution = new FutureTask<>(() -> {
            run(task, job);
            return null;
        });
        executor.execute(task.execution);
        if (evicted != null) tasks.remove(evicted.id);
        tasks.put(task.id, task);
        return task.snapshot();
    }

    public synchronized Optional<Snapshot> get(String tenantId, UUID id) {
        return ownedTask(tenantId, id).map(Task::snapshot);
    }

    public synchronized Optional<Snapshot> cancel(String tenantId, UUID id) {
        return ownedTask(tenantId, id).map(task -> {
            cancel(task);
            return task.snapshot();
        });
    }

    @Override
    public synchronized void close() {
        tasks.values().forEach(this::cancel);
        executor.shutdownNow();
    }

    private Optional<Task> ownedTask(String tenantId, UUID id) {
        requireTenant(tenantId);
        return Optional.ofNullable(tasks.get(Objects.requireNonNull(id)))
                .filter(task -> task.tenantId.equals(tenantId));
    }

    private void cancel(Task task) {
        if (task.status.terminal()) return;
        finish(task, Status.CANCELED, null);
        task.execution.cancel(true);
        executor.remove(task.execution);
    }

    private void run(Task task, BacktestJob job) throws Exception {
        synchronized (this) {
            if (task.status != Status.QUEUED) return;
            task.status = Status.RUNNING;
            task.startedAt = Instant.now();
        }
        try {
            var result = Objects.requireNonNull(job.run());
            finishIfRunning(task, Status.SUCCEEDED, result);
        } finally {
            finishIfRunning(task, Status.FAILED, null);
        }
    }

    private synchronized void finishIfRunning(Task task, Status status, BacktestEngine.ReplayResult result) {
        if (task.status == Status.RUNNING) finish(task, status, result);
    }

    private void finish(Task task, Status status, BacktestEngine.ReplayResult result) {
        task.status = status;
        task.result = result;
        task.finishedAt = Instant.now();
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenant ID is required");
    }
}
