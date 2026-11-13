package io.github.quant.server.order;

import io.github.quant.broker.spi.BrokerAdapter;
import io.github.quant.domain.Instrument;
import io.github.quant.oms.LimitOrder;
import io.github.quant.risk.PreTradeRiskPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static io.github.quant.server.order.PostgresOrderRepository.Status.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "quant.postgres.url", matches = "jdbc:postgresql:.+")
class PostgresOrderManagementServiceTest {
    private static final String ACCOUNT = "sim-account";
    private static final Instrument INSTRUMENT = new Instrument("600000", "SSE");
    private static final LimitOrder.Request REQUEST = new LimitOrder.Request(INSTRUMENT, LimitOrder.Side.BUY,
            100, new BigDecimal("10.00"));
    private static final Duration DEADLINE = Duration.ofMinutes(1);
    private final DataSource dataSource = new TestDataSource();
    private final PreTradeRiskPolicy risk = new PreTradeRiskPolicy(Map.of(ACCOUNT, Map.of(
            new PreTradeRiskPolicy.InstrumentKey("SSE", "600000"),
            new PreTradeRiskPolicy.Limits(100, new BigDecimal("1000.00")))));

    @BeforeEach
    void migrate() throws Exception {
        try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V002__broker_orders.sql"));
             var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS broker_order");
            statement.execute(new String(source.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void retriesAcrossInstancesWithoutResendingAndReconcilesDefinitiveEvidence() throws Exception {
        var submits = new AtomicInteger();
        var lookups = new AtomicInteger();
        BrokerAdapter adapter = new BrokerAdapter() {
            @Override public SubmissionResult submit(Submission request) throws IOException {
                submits.incrementAndGet();
                throw new IOException("simulated lost acknowledgement");
            }
            @Override public Evidence reconcile(SubmissionKey key) {
                return lookups.incrementAndGet() == 1 ? new Absent() : new Accepted("sim-order-1");
            }
        };
        var first = service(adapter);
        assertEquals(UNKNOWN, first.submit(ACCOUNT, "client-1", REQUEST).status());
        var reopened = service(adapter);
        var equivalent = new LimitOrder.Request(INSTRUMENT, LimitOrder.Side.BUY, 100, BigDecimal.TEN);
        assertEquals(UNKNOWN, reopened.submit(ACCOUNT, "client-1", equivalent).status());
        var conflicting = new LimitOrder.Request(INSTRUMENT, LimitOrder.Side.SELL, 100, BigDecimal.TEN);
        assertThrows(IllegalArgumentException.class, () -> reopened.submit(ACCOUNT, "client-1", conflicting));
        assertEquals(UNKNOWN, reopened.reconcile(ACCOUNT, "client-1").orElseThrow().status());
        assertEquals(ACKNOWLEDGED, reopened.reconcile(ACCOUNT, "client-1").orElseThrow().status());
        assertEquals("sim-order-1", first.get(ACCOUNT, "client-1").orElseThrow().brokerOrderId());
        assertEquals(REJECTED, reopened.submit("other-account", "client-1", REQUEST).status());
        assertEquals(1, submits.get());
    }

    @Test
    void recoversAnAbandonedReservationWithoutDispatchingAgain() throws Exception {
        var key = new BrokerAdapter.SubmissionKey(ACCOUNT, "abandoned");
        var request = new BrokerAdapter.Submission(key, "SSE", "600000", BrokerAdapter.Side.BUY,
                100, REQUEST.limit());
        var repository = new PostgresOrderRepository(dataSource);
        assertTrue(repository.reserve(request, DEADLINE).created());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE broker_order SET submit_deadline = clock_timestamp() - interval '1 second'");
        }
        var restarted = service(new BrokerAdapter() {
            @Override public SubmissionResult submit(Submission ignored) {
                throw new AssertionError("an abandoned key must not be dispatched again");
            }
            @Override public Evidence reconcile(SubmissionKey ignored) { return new Absent(); }
        });
        assertEquals(UNKNOWN, restarted.submit(ACCOUNT, "abandoned", REQUEST).status());
        assertEquals(UNKNOWN, restarted.reconcile(ACCOUNT, "abandoned").orElseThrow().status());
    }

    private PostgresOrderManagementService service(BrokerAdapter adapter) {
        return new PostgresOrderManagementService(new PostgresOrderRepository(dataSource), adapter, risk, DEADLINE);
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
