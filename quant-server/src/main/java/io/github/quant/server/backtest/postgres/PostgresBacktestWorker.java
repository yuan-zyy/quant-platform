package io.github.quant.server.backtest.postgres;

import io.github.quant.backtest.BacktestEngine;
import io.github.quant.server.backtest.BacktestTaskScheduler.BacktestJob;
import io.github.quant.server.backtest.BacktestTaskService.Result;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls and executes one durable backtest at a time with a renewable, fenced lease. */
public final class PostgresBacktestWorker implements AutoCloseable {
    @FunctionalInterface
    public interface VersionedScenarioResolver {
        Optional<BacktestJob> resolve(String tenantId, String scenarioId, String scenarioVersion);
    }

    private final PostgresBacktestRepository repository;
    private final VersionedScenarioResolver scenarios;
    private final String owner;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final Duration recoveryBackoff;
    private Thread running;
    private boolean closed;

    public PostgresBacktestWorker(PostgresBacktestRepository repository, VersionedScenarioResolver scenarios,
                                   String owner, Duration leaseDuration, int maxAttempts) {
        this(repository, scenarios, owner, leaseDuration, maxAttempts, Duration.ZERO);
    }

    public PostgresBacktestWorker(PostgresBacktestRepository repository, VersionedScenarioResolver scenarios,
                                   String owner, Duration leaseDuration, int maxAttempts, Duration recoveryBackoff) {
        this.repository = Objects.requireNonNull(repository);
        this.scenarios = Objects.requireNonNull(scenarios);
        if (owner.isBlank() || leaseDuration.toMillis() < 3 || maxAttempts < 1 || recoveryBackoff.isNegative()) {
            throw new IllegalArgumentException("invalid backtest worker configuration");
        }
        this.owner = owner;
        this.leaseDuration = leaseDuration;
        this.maxAttempts = maxAttempts;
        this.recoveryBackoff = recoveryBackoff;
    }

    /** Recovers a bounded batch of expired leases and runs at most one available job. */
    public boolean runOnce() throws SQLException, InterruptedException {
        Thread current = Thread.currentThread();
        synchronized (this) {
            if (closed) throw new IllegalStateException("backtest worker is closed");
            if (running != null) throw new IllegalStateException("backtest worker is already polling");
            running = current;
        }
        try {
            checkInterrupted();
            repository.recoverExpired(maxAttempts, recoveryBackoff, 32);
            checkInterrupted();
            var lease = repository.claim(owner, leaseDuration, maxAttempts);
            if (lease.isEmpty()) return false;
            execute(lease.get(), current);
            return true;
        } finally {
            synchronized (this) {
                running = null;
            }
        }
    }

    private void execute(PostgresBacktestRepository.Lease lease, Thread jobThread)
            throws SQLException, InterruptedException {
        var renewal = new Object();
        var stopped = new AtomicBoolean();
        var lost = new AtomicBoolean();
        Thread heartbeat = Thread.ofVirtual().name("backtest-lease-heartbeat").start(() -> {
            try {
                while (!stopped.get()) {
                    Thread.sleep(leaseDuration.dividedBy(3));
                    synchronized (renewal) {
                        if (stopped.get()) return;
                        try {
                            if (repository.renew(lease, leaseDuration)) continue;
                        } catch (SQLException | RuntimeException failure) {
                            // The worker can no longer rely on retaining this lease.
                        }
                        lost.set(true);
                        jobThread.interrupt();
                        return;
                    }
                }
            } catch (InterruptedException stoppedHeartbeat) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            checkInterrupted();
            var task = lease.task();
            BacktestEngine.ReplayResult result = null;
            String failureCode = null;
            try {
                var job = scenarios.resolve(task.tenantId(), task.scenarioId(), task.scenarioVersion());
                if (job.isEmpty()) {
                    failureCode = "scenario_unavailable";
                } else {
                    result = Objects.requireNonNull(job.get().run());
                }
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception failed) {
                failureCode = "scenario_failed";
            }
            synchronized (renewal) {
                stopped.set(true);
                checkInterrupted();
                if (lost.get()) return;
                if (failureCode == null) {
                    repository.succeed(lease, PostgresBacktestTaskService.encode(Result.from(result.metrics())));
                } else {
                    repository.fail(lease, failureCode);
                }
            }
        } finally {
            heartbeat.interrupt();
            synchronized (renewal) {
                stopped.set(true);
            }
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("backtest worker interrupted");
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (running != null) running.interrupt();
    }
}
