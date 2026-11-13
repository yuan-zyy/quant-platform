package io.github.quant.server.backtest.postgres;

import io.github.quant.backtest.BacktestEngine;
import io.github.quant.backtest.ExecutionCosts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static io.github.quant.server.backtest.postgres.PostgresBacktestRepository.Status.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "quant.postgres.url", matches = "jdbc:postgresql:.+")
class PostgresBacktestRepositoryTest {
    private static final Duration LEASE = Duration.ofMinutes(1);
    private final DataSource dataSource = new TestDataSource();

    @BeforeEach
    void migrate() throws Exception {
        try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V001__backtest_tasks.sql"));
             var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS backtest_task");
            statement.execute(new String(source.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void concurrentTenantIdempotencyPreservesTheFirstBoundScenarioVersion() throws Exception {
        var repository = new PostgresBacktestRepository(dataSource);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = threads.submit(() -> {
                start.await();
                return repository.submit("tenant-a", "retry-key", "daily", "v1");
            });
            var second = threads.submit(() -> {
                start.await();
                return repository.submit("tenant-a", "retry-key", "daily", "v1");
            });
            start.countDown();
            var task = first.get(10, TimeUnit.SECONDS);
            assertEquals(task.id(), second.get(10, TimeUnit.SECONDS).id());
            var retried = repository.submit("tenant-a", "retry-key", "daily", "v2");
            assertEquals(task.id(), retried.id());
            assertEquals("v1", retried.scenarioVersion());
            assertThrows(PostgresBacktestRepository.IdempotencyConflictException.class,
                    () -> repository.submit("tenant-a", "retry-key", "different", "v2"));
            var other = repository.submit("tenant-b", "retry-key", "daily", "v1");
            assertNotEquals(task.id(), other.id());
            assertTrue(repository.get("tenant-b", task.id()).isEmpty());
            assertTrue(repository.cancel("tenant-b", task.id()).isEmpty());
        }
    }

    @Test
    void locksClaimsAndFencesExpiredCanceledAndCompletedAttempts() throws Exception {
        var repository = new PostgresBacktestRepository(dataSource);
        var task = repository.submit("tenant", null, "daily", "v1");
        PostgresBacktestRepository.Lease previous;
        try (var threads = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = threads.submit(() -> {
                start.await();
                return repository.claim("first", LEASE, 2);
            });
            var second = threads.submit(() -> {
                start.await();
                return repository.claim("second", LEASE, 2);
            });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(a.isPresent(), b.isPresent());
            previous = a.orElseGet(b::orElseThrow);
        }
        assertEquals(task.id(), previous.task().id());
        assertTrue(repository.renew(previous, LEASE));
        expire();
        assertFalse(repository.renew(previous, LEASE));
        assertFalse(repository.succeed(previous, "{}"));
        var reopened = new PostgresBacktestRepository(dataSource);
        assertEquals(1, reopened.recoverExpired(2, Duration.ZERO, 10));
        assertEquals(QUEUED, reopened.get("tenant", task.id()).orElseThrow().status());
        var next = reopened.claim("second-run", LEASE, 2).orElseThrow();
        assertTrue(next.epoch() > previous.epoch());
        assertEquals(2, next.task().attemptCount());
        assertFalse(reopened.succeed(previous, "{}"));
        assertEquals(CANCELED, reopened.cancel("tenant", task.id()).orElseThrow().status());
        assertFalse(reopened.succeed(next, "{}"));
        assertFalse(reopened.fail(next, "late_failure"));

        var completed = repository.submit("tenant", null, "daily", "v2");
        var completedLease = reopened.claim("completion", LEASE, 2).orElseThrow();
        assertEquals(completed.id(), completedLease.task().id());
        assertTrue(reopened.succeed(completedLease, "{\"schemaVersion\":1,\"endingEquity\":\"10075.00\"}"));
        var result = new PostgresBacktestRepository(dataSource).get("tenant", completed.id()).orElseThrow();
        assertEquals(SUCCEEDED, result.status());
        assertTrue(result.resultJson().contains("10075.00"));
        assertEquals(SUCCEEDED, repository.cancel("tenant", completed.id()).orElseThrow().status());
    }

    @Test
    void anExhaustedLeaseBecomesFailedWithoutAnExtraClaim() throws Exception {
        var repository = new PostgresBacktestRepository(dataSource);
        var task = repository.submit("tenant", null, "daily", "v1");
        assertTrue(repository.claim("only-attempt", LEASE, 1).isPresent());
        expire();
        assertEquals(1, repository.recoverExpired(1, Duration.ZERO, 10));
        var failed = repository.get("tenant", task.id()).orElseThrow();
        assertEquals(FAILED, failed.status());
        assertEquals(1, failed.attemptCount());
        assertNotNull(failed.failureCode());
        assertTrue(repository.claim("extra-attempt", LEASE, 1).isEmpty());
    }

    @Test
    void aWorkerUsesTheBoundVersionAndPersistsAReadableResult() throws Exception {
        var repository = new PostgresBacktestRepository(dataSource);
        var tasks = new PostgresBacktestTaskService(repository, (tenant, scenario) -> Optional.of("v1"));
        var queued = tasks.submit("tenant", "daily", "replay-key").orElseThrow();
        try (var worker = new PostgresBacktestWorker(repository, (tenant, scenario, version) -> {
            assertEquals("tenant", tenant);
            assertEquals("daily", scenario);
            assertEquals("v1", version);
            return Optional.of(() -> new BacktestEngine().run(consumer -> {}, new BigDecimal("10000.00"),
                    Map.of(), ExecutionCosts.NONE, (close, context) -> List.of()));
        }, "worker", LEASE, 2)) {
            assertTrue(worker.runOnce());
            assertFalse(worker.runOnce());
        }
        var reopened = new PostgresBacktestTaskService(new PostgresBacktestRepository(dataSource),
                (tenant, scenario) -> Optional.of("v2"));
        assertEquals(queued.id(), reopened.submit("tenant", "daily", "replay-key").orElseThrow().id());
        var done = reopened.get("tenant", queued.id()).orElseThrow();
        assertEquals("SUCCEEDED", done.status().name());
        assertEquals("10000.00", done.result().endingEquity());
        assertEquals(0, done.result().fillCount());
    }

    private void expire() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE backtest_task SET lease_expires_at = clock_timestamp() - interval '1 second'"
                    + " WHERE status = 'RUNNING'");
        }
    }

    private static final class TestDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            return getConnection(System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
        }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return DriverManager.getConnection(System.getProperty("quant.postgres.url"), user, password);
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("Unsupported"); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
