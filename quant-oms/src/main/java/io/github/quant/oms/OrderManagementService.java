package io.github.quant.oms;

import io.github.quant.broker.spi.BrokerAdapter;
import io.github.quant.broker.spi.BrokerAdapter.SubmissionKey;
import io.github.quant.risk.PreTradeRiskPolicy;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** In-memory order submission and reconciliation coordinator for a simulated broker adapter. */
public final class OrderManagementService {
    public enum Status { SUBMITTING, UNKNOWN, ACKNOWLEDGED, REJECTED, NOT_FOUND }

    public record Snapshot(SubmissionKey key, LimitOrder.Request request, Status status,
                           String brokerOrderId, String rejectionCode) {}

    private static final class Entry {
        final LimitOrder.Request request;
        Status status = Status.SUBMITTING;
        String brokerOrderId;
        String rejectionCode;
        boolean reconciling;

        Entry(LimitOrder.Request request) { this.request = request; }

        Snapshot snapshot(SubmissionKey key) {
            return new Snapshot(key, request, status, brokerOrderId, rejectionCode);
        }
    }

    private final BrokerAdapter broker;
    private final Function<BrokerAdapter.Submission, PreTradeRiskPolicy.Decision> admission;
    private final Map<SubmissionKey, Entry> orders = new HashMap<>();

    public OrderManagementService(BrokerAdapter broker) {
        this(broker, ignored -> PreTradeRiskPolicy.Decision.ALLOWED);
    }

    public OrderManagementService(BrokerAdapter broker, PreTradeRiskPolicy risk) {
        this(broker, Objects.requireNonNull(risk)::evaluate);
    }

    private OrderManagementService(BrokerAdapter broker,
                                   Function<BrokerAdapter.Submission, PreTradeRiskPolicy.Decision> admission) {
        this.broker = Objects.requireNonNull(broker);
        this.admission = admission;
    }

    /** Reserves the order key before making the first and only submission attempt. */
    public Snapshot submit(String accountId, String clientOrderId, LimitOrder.Request request)
            throws InterruptedException {
        var key = new SubmissionKey(accountId, clientOrderId);
        Objects.requireNonNull(request);
        var instrument = request.instrument();
        var submission = new BrokerAdapter.Submission(key, instrument.exchange(), instrument.symbol(),
                BrokerAdapter.Side.valueOf(request.side().name()), request.quantity(), request.limit());
        Entry entry;
        synchronized (orders) {
            var existing = orders.get(key);
            if (existing != null) {
                if (!sameRequest(existing.request, request)) {
                    throw new IllegalArgumentException("client order ID conflicts with an existing submission");
                }
                return existing.snapshot(key);
            }
            entry = new Entry(request);
            var decision = admission.apply(submission);
            orders.put(key, entry);
            if (decision != PreTradeRiskPolicy.Decision.ALLOWED) {
                entry.status = Status.REJECTED;
                entry.rejectionCode = "RISK_" + decision.name();
                return entry.snapshot(key);
            }
        }

        try {
            var outcome = Objects.requireNonNull(broker.submit(submission));
            synchronized (orders) {
                switch (outcome) {
                    case BrokerAdapter.Acknowledged acknowledged -> {
                        entry.brokerOrderId = acknowledged.brokerOrderId();
                        entry.status = Status.ACKNOWLEDGED;
                    }
                    case BrokerAdapter.Rejected rejected -> {
                        entry.rejectionCode = rejected.code();
                        entry.status = Status.REJECTED;
                    }
                    case BrokerAdapter.Unknown ignored -> entry.status = Status.UNKNOWN;
                }
                return entry.snapshot(key);
            }
        } catch (IOException unavailable) {
            return unknown(key, entry);
        } catch (InterruptedException | RuntimeException failure) {
            unknown(key, entry);
            throw failure;
        }
    }

    public Optional<Snapshot> get(String accountId, String clientOrderId) {
        var key = new SubmissionKey(accountId, clientOrderId);
        synchronized (orders) {
            return Optional.ofNullable(orders.get(key)).map(entry -> entry.snapshot(key));
        }
    }

    /** Resolves uncertain orders using explicit broker evidence without resubmitting. */
    public Optional<Snapshot> reconcile(String accountId, String clientOrderId) throws InterruptedException {
        var key = new SubmissionKey(accountId, clientOrderId);
        Entry entry;
        synchronized (orders) {
            entry = orders.get(key);
            if (entry == null) return Optional.empty();
            if (entry.status != Status.UNKNOWN || entry.reconciling) return Optional.of(entry.snapshot(key));
            entry.reconciling = true;
        }
        try {
            var evidence = Objects.requireNonNull(broker.reconcile(key));
            synchronized (orders) {
                switch (evidence) {
                    case BrokerAdapter.Accepted accepted -> {
                        entry.brokerOrderId = accepted.brokerOrderId();
                        entry.status = Status.ACKNOWLEDGED;
                    }
                    case BrokerAdapter.Declined declined -> {
                        entry.rejectionCode = declined.code();
                        entry.status = Status.REJECTED;
                    }
                    case BrokerAdapter.Absent ignored -> entry.status = Status.NOT_FOUND;
                    case BrokerAdapter.Inconclusive ignored -> { }
                }
                return Optional.of(entry.snapshot(key));
            }
        } catch (IOException unavailable) {
            return Optional.of(unknown(key, entry));
        } finally {
            synchronized (orders) {
                entry.reconciling = false;
            }
        }
    }

    private Snapshot unknown(SubmissionKey key, Entry entry) {
        synchronized (orders) {
            entry.status = Status.UNKNOWN;
            return entry.snapshot(key);
        }
    }

    private static boolean sameRequest(LimitOrder.Request original, LimitOrder.Request retry) {
        return original.instrument().equals(retry.instrument()) && original.side() == retry.side()
                && original.quantity() == retry.quantity() && original.limit().compareTo(retry.limit()) == 0;
    }
}
