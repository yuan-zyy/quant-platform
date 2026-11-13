package io.github.quant.server;

import io.github.quant.backtest.BacktestEngine;
import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.broker.spi.BrokerAdapter;
import io.github.quant.risk.PreTradeRiskPolicy;
import io.github.quant.server.backtest.postgres.PostgresBacktestRepository;
import io.github.quant.server.backtest.postgres.PostgresBacktestTaskService;
import io.github.quant.server.backtest.postgres.PostgresBacktestWorker;
import io.github.quant.server.order.PostgresOrderManagementService;
import io.github.quant.server.order.PostgresOrderReconciliationWorker;
import io.github.quant.server.order.PostgresOrderRepository;
import io.github.quant.server.order.PostgresReconciliationCandidates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "quant.postgres.restore.url", matches = "jdbc:postgresql:.+")
class PostgresRestoreSmokeTest {
    private static final String TENANT = "tenant-a";
    private static final String ACCOUNT = "account-a";
    private static final UUID QUEUED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FINISHED = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EXPIRED = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Duration LEASE = Duration.ofMinutes(1);
    private final DataSource dataSource = new RestoreDataSource();

    @Test
    void restoredWorkerCompletesQueuedAndExpiredTasksAndFencesOldAttempts() throws Exception {
        var repository = new PostgresBacktestRepository(dataSource);
        assertEquals(PostgresBacktestRepository.Status.QUEUED, repository.get(TENANT, QUEUED).orElseThrow().status());
        var finished = repository.get(TENANT, FINISHED).orElseThrow();
        assertEquals(PostgresBacktestRepository.Status.SUCCEEDED, finished.status());
        var expired = repository.get(TENANT, EXPIRED).orElseThrow();
        assertEquals(PostgresBacktestRepository.Status.RUNNING, expired.status());
        assertEquals(1, expired.attemptCount());
        var stale = new PostgresBacktestRepository.Lease(expired, "old-worker", 1, Instant.EPOCH);
        assertFalse(repository.renew(stale, LEASE));
        assertFalse(repository.succeed(stale, "{}"));
        assertTrue(repository.get("tenant-b", EXPIRED).isEmpty());

        var calls = new AtomicInteger();
        try (var worker = new PostgresBacktestWorker(repository, (tenant, scenario, version) -> {
            assertEquals(TENANT, tenant);
            assertEquals("demo-buy-hold", scenario);
            assertEquals("v1", version);
            return Optional.of(() -> {
                calls.incrementAndGet();
                return new BacktestEngine().run(consumer -> {}, new BigDecimal("10000.00"),
                        Map.of(), ExecutionCosts.NONE, (close, context) -> List.of());
            });
        }, "restore-new-worker", LEASE, 2)) {
            assertTrue(worker.runOnce());
            assertTrue(worker.runOnce());
            assertFalse(worker.runOnce());
        }
        assertEquals(2, calls.get());
        assertEquals(finished, repository.get(TENANT, FINISHED).orElseThrow());
        assertEquals(2, repository.get(TENANT, EXPIRED).orElseThrow().attemptCount());
        assertFalse(repository.succeed(stale, "{}"));
        var service = new PostgresBacktestTaskService(repository, (tenant, scenario) -> Optional.empty());
        assertEquals(new BigDecimal("10010.00"), service.get(TENANT, FINISHED).orElseThrow().result().endingEquity());
        for (UUID id : List.of(QUEUED, EXPIRED)) {
            var result = service.get(TENANT, id).orElseThrow();
            assertEquals("SUCCEEDED", result.status().name());
            assertEquals(new BigDecimal("10000.00"), result.result().endingEquity());
        }
    }

    @Test
    void restoredOmsReconcilesOnlyDueUncertainOrdersWithoutSubmitting() throws Exception {
        var repository = new PostgresOrderRepository(dataSource);
        var examined = new ArrayList<String>();
        var adapter = new BrokerAdapter() {
            @Override public SubmissionResult submit(Submission submission) {
                throw new AssertionError("restored orders must never be dispatched again");
            }

            @Override public Evidence reconcile(SubmissionKey key) {
                assertEquals(ACCOUNT, key.accountId());
                examined.add(key.clientOrderId());
                return new Accepted("restored-" + key.clientOrderId());
            }
        };
        var service = new PostgresOrderManagementService(repository, adapter,
                new PreTradeRiskPolicy(Map.of()), LEASE);
        try (var worker = new PostgresOrderReconciliationWorker(new PostgresReconciliationCandidates(dataSource),
                service, 8, Duration.ZERO, LEASE, LEASE)) {
            assertEquals(2, worker.runOnce());
            assertEquals(0, worker.runOnce());
        }
        assertEquals(List.of("submitting-1", "unknown-1"), examined.stream().sorted().toList());
        for (String id : examined) {
            var row = service.get(ACCOUNT, id).orElseThrow();
            assertEquals(PostgresOrderRepository.Status.ACKNOWLEDGED, row.status());
            assertEquals("restored-" + id, row.brokerOrderId());
        }
        assertEquals(PostgresOrderRepository.Status.ACKNOWLEDGED,
                service.get(ACCOUNT, "acknowledged-1").orElseThrow().status());
        assertEquals(PostgresOrderRepository.Status.UNKNOWN,
                service.get(ACCOUNT, "deferred-1").orElseThrow().status());
    }

    private static final class RestoreDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            return getConnection(System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
        }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return DriverManager.getConnection(System.getProperty("quant.postgres.restore.url"), user, password);
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("Unsupported"); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
