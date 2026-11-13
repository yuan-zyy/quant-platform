package io.github.quant.server.order;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs bounded reconciliation batches for orders already submitted through the durable OMS. */
public final class PostgresOrderReconciliationWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(PostgresOrderReconciliationWorker.class.getName());

    private final PostgresReconciliationCandidates candidates;
    private final PostgresOrderManagementService orders;
    private final int batchSize;
    private final Duration minimumAge;
    private final Duration retryDelay;
    private final long pollMillis;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("order-reconciliation").factory());
    private boolean started;
    private boolean closed;
    private Thread running;

    public PostgresOrderReconciliationWorker(PostgresReconciliationCandidates candidates,
                                             PostgresOrderManagementService orders, int batchSize,
                                             Duration minimumAge, Duration retryDelay, Duration pollInterval) {
        this.candidates = Objects.requireNonNull(candidates);
        this.orders = Objects.requireNonNull(orders);
        this.minimumAge = Objects.requireNonNull(minimumAge);
        this.retryDelay = Objects.requireNonNull(retryDelay);
        pollMillis = pollInterval.toMillis();
        if (batchSize < 1 || minimumAge.isNegative() || retryDelay.toMillis() < 1 || pollMillis < 1) {
            throw new IllegalArgumentException("invalid reconciliation polling configuration");
        }
        this.batchSize = batchSize;
    }

    public synchronized void start() {
        if (closed || started || running != null) {
            throw new IllegalStateException("reconciliation worker is closed or already active");
        }
        started = true;
        executor.scheduleWithFixedDelay(this::poll, 0, pollMillis, TimeUnit.MILLISECONDS);
    }

    /** Claims and reconciles at most one batch. Use before scheduling the worker. */
    public int runOnce() throws SQLException, InterruptedException {
        return runBatch(false);
    }

    private int runBatch(boolean scheduled) throws SQLException, InterruptedException {
        synchronized (this) {
            if (closed || running != null || (started && !scheduled)) {
                throw new IllegalStateException("reconciliation worker is closed or already active");
            }
            running = Thread.currentThread();
        }
        try {
            checkInterrupted();
            var batch = candidates.claimReconciliationCandidates(batchSize, minimumAge, retryDelay);
            SQLException failures = null;
            for (var key : batch) {
                checkInterrupted();
                try {
                    orders.reconcile(key.accountId(), key.clientOrderId());
                } catch (SQLException failure) {
                    if (failures == null) failures = failure;
                    else failures.addSuppressed(failure);
                }
            }
            if (failures != null) throw failures;
            return batch.size();
        } finally {
            synchronized (this) {
                running = null;
            }
        }
    }

    private void poll() {
        try {
            runBatch(true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (SQLException | RuntimeException failure) {
            synchronized (this) {
                if (!closed) LOG.log(System.Logger.Level.WARNING, "Order reconciliation poll failed");
            }
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("order reconciliation interrupted");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        executor.shutdownNow();
        if (running != null) running.interrupt();
    }
}
