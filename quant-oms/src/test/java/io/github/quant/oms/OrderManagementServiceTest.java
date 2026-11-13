package io.github.quant.oms;

import io.github.quant.broker.spi.BrokerAdapter;
import io.github.quant.domain.Instrument;
import io.github.quant.risk.PreTradeRiskPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.quant.oms.OrderManagementService.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderManagementServiceTest {
    private static final Instrument STOCK = new Instrument("600000", "SSE");
    private static final LimitOrder.Request REQUEST = new LimitOrder.Request(
            STOCK, LimitOrder.Side.BUY, 100, new BigDecimal("10.00"));

    @Test
    void riskRejectionIsStableAndDoesNotReachTheAdapter() throws Exception {
        var calls = new AtomicInteger();
        var risk = new PreTradeRiskPolicy(Map.of("account-a", Map.of(
                new PreTradeRiskPolicy.InstrumentKey("SSE", "600000"),
                new PreTradeRiskPolicy.Limits(100, new BigDecimal("1000.00")))));
        var oms = new OrderManagementService(new BrokerAdapter() {
            @Override public SubmissionResult submit(Submission submission) {
                calls.incrementAndGet();
                return new Acknowledged("sim-1");
            }

            @Override public Evidence reconcile(SubmissionKey key) {
                throw new AssertionError("risk rejections must never reach the broker");
            }
        }, risk);
        var rejected = oms.submit("account-b", "same-key", REQUEST);
        assertEquals(REJECTED, rejected.status());
        assertEquals("RISK_ACCOUNT_NOT_CONFIGURED", rejected.rejectionCode());
        assertEquals(rejected, oms.submit("account-b", "same-key", REQUEST));
        assertEquals(rejected, oms.reconcile("account-b", "same-key").orElseThrow());
        assertEquals(0, calls.get());
        assertEquals(ACKNOWLEDGED, oms.submit("account-a", "same-key", REQUEST).status());
        assertEquals(1, calls.get());
    }

    @Test
    void neverResubmitsAnUncertainOrderAndResolvesItFromBrokerEvidence() throws Exception {
        var sends = new AtomicInteger();
        var lookups = new AtomicInteger();
        var oms = new OrderManagementService(new BrokerAdapter() {
            @Override public SubmissionResult submit(Submission request) throws IOException {
                sends.incrementAndGet();
                assertEquals("account-a", request.key().accountId());
                assertEquals("SSE", request.exchange());
                assertEquals("600000", request.symbol());
                throw new IOException("response lost after broker acceptance");
            }

            @Override public Evidence reconcile(SubmissionKey key) throws IOException {
                return switch (lookups.incrementAndGet()) {
                    case 1 -> throw new IOException("lookup unavailable");
                    case 2 -> new Inconclusive();
                    default -> new Accepted("broker-123");
                };
            }
        });

        assertEquals(UNKNOWN, oms.submit("account-a", "client-1", REQUEST).status());
        assertEquals(UNKNOWN, oms.submit("account-a", "client-1", new LimitOrder.Request(
                STOCK, LimitOrder.Side.BUY, 100, BigDecimal.TEN)).status());
        assertThrows(IllegalArgumentException.class, () -> oms.submit("account-a", "client-1",
                new LimitOrder.Request(STOCK, LimitOrder.Side.SELL, 100, BigDecimal.TEN)));
        assertTrue(oms.get("account-b", "client-1").isEmpty());
        assertTrue(oms.reconcile("account-b", "client-1").isEmpty());
        assertEquals(UNKNOWN, oms.reconcile("account-a", "client-1").orElseThrow().status());
        assertEquals(UNKNOWN, oms.reconcile("account-a", "client-1").orElseThrow().status());
        var accepted = oms.reconcile("account-a", "client-1").orElseThrow();
        assertEquals(ACKNOWLEDGED, accepted.status());
        assertEquals("broker-123", accepted.brokerOrderId());
        assertEquals(accepted, oms.reconcile("account-a", "client-1").orElseThrow());
        assertEquals(1, sends.get());
        assertEquals(3, lookups.get());
    }

    @Test
    void reservesKeyBeforeAdapterCallWithoutHoldingTheGlobalLock() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sends = new AtomicInteger();
        var oms = new OrderManagementService(new BrokerAdapter() {
            @Override public SubmissionResult submit(Submission request) throws InterruptedException {
                sends.incrementAndGet();
                if (request.key().accountId().equals("account-a")) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("submit was never released");
                }
                return new Acknowledged("broker-" + request.key().accountId());
            }

            @Override public Evidence reconcile(SubmissionKey key) {
                throw new AssertionError("submitting or terminal orders must not reconcile");
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> oms.submit("account-a", "same-id", REQUEST));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var retry = executor.submit(() -> oms.submit("account-a", "same-id", REQUEST));
                assertEquals(SUBMITTING, retry.get(2, TimeUnit.SECONDS).status());
                assertEquals(SUBMITTING, oms.reconcile("account-a", "same-id").orElseThrow().status());
                var other = executor.submit(() -> oms.submit("account-b", "same-id", REQUEST));
                assertEquals(ACKNOWLEDGED, other.get(2, TimeUnit.SECONDS).status());
            } finally {
                release.countDown();
            }
            assertEquals(ACKNOWLEDGED, first.get(2, TimeUnit.SECONDS).status());
            assertEquals(2, sends.get());
        }
    }

    @Test
    void distinguishesFinalRejectionAndAbsenceAndRetainsInterruptedAttempt() throws Exception {
        var oms = new OrderManagementService(new BrokerAdapter() {
            @Override public SubmissionResult submit(Submission request) throws InterruptedException {
                return switch (request.key().clientOrderId()) {
                    case "reject" -> new Rejected("risk_limit");
                    case "interrupt" -> throw new InterruptedException("send interrupted");
                    default -> new Unknown();
                };
            }

            @Override public Evidence reconcile(SubmissionKey key) {
                return key.clientOrderId().equals("absent") ? new Absent() : new Declined("invalid_order");
            }
        });
        var rejected = oms.submit("account-a", "reject", REQUEST);
        assertEquals(REJECTED, rejected.status());
        assertEquals("risk_limit", rejected.rejectionCode());
        assertEquals(rejected, oms.reconcile("account-a", "reject").orElseThrow());
        assertEquals(UNKNOWN, oms.submit("account-a", "absent", REQUEST).status());
        assertEquals(NOT_FOUND, oms.reconcile("account-a", "absent").orElseThrow().status());
        assertEquals(NOT_FOUND, oms.submit("account-a", "absent", REQUEST).status());
        assertThrows(InterruptedException.class, () -> oms.submit("account-a", "interrupt", REQUEST));
        assertEquals(UNKNOWN, oms.get("account-a", "interrupt").orElseThrow().status());
        var declined = oms.reconcile("account-a", "interrupt").orElseThrow();
        assertEquals(REJECTED, declined.status());
        assertEquals("invalid_order", declined.rejectionCode());
    }
}
