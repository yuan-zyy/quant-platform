package io.github.quant.server.order;

import io.github.quant.broker.spi.BrokerAdapter;
import io.github.quant.broker.spi.BrokerAdapter.SubmissionKey;
import io.github.quant.oms.LimitOrder;
import io.github.quant.risk.PreTradeRiskPolicy;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Durable submission coordinator for a simulated adapter. A reserved key is never submitted again. */
public final class PostgresOrderManagementService {
    private final PostgresOrderRepository repository;
    private final BrokerAdapter broker;
    private final PreTradeRiskPolicy risk;
    private final Duration submitDeadline;

    public PostgresOrderManagementService(PostgresOrderRepository repository, BrokerAdapter broker,
                                         PreTradeRiskPolicy risk, Duration submitDeadline) {
        this.repository = Objects.requireNonNull(repository);
        this.broker = Objects.requireNonNull(broker);
        this.risk = Objects.requireNonNull(risk);
        if (submitDeadline.toMillis() <= 0) {
            throw new IllegalArgumentException("submission deadline must be at least one millisecond");
        }
        this.submitDeadline = submitDeadline;
    }

    public PostgresOrderRepository.Order submit(String accountId, String clientOrderId, LimitOrder.Request request)
            throws SQLException, InterruptedException {
        var key = new SubmissionKey(accountId, clientOrderId);
        var instrument = request.instrument();
        var submission = new BrokerAdapter.Submission(key, instrument.exchange(), instrument.symbol(),
                BrokerAdapter.Side.valueOf(request.side().name()), request.quantity(), request.limit());
        var reservation = repository.reserve(submission, submitDeadline);
        if (!reservation.created()) {
            return repository.recoverExpired(key).orElse(reservation.order());
        }

        var decision = risk.evaluate(submission);
        if (decision != PreTradeRiskPolicy.Decision.ALLOWED) {
            return repository.complete(key, new BrokerAdapter.Rejected("RISK_" + decision.name()));
        }
        var current = repository.recoverExpired(key).orElseThrow(
                () -> new SQLException("reserved order was not found"));
        if (current.status() != PostgresOrderRepository.Status.SUBMITTING) return current;

        BrokerAdapter.SubmissionResult outcome;
        try {
            outcome = Objects.requireNonNull(broker.submit(submission));
        } catch (IOException ambiguous) {
            outcome = new BrokerAdapter.Unknown();
        } catch (InterruptedException | RuntimeException ambiguous) {
            try {
                repository.complete(key, new BrokerAdapter.Unknown());
            } catch (SQLException persistenceFailure) {
                ambiguous.addSuppressed(persistenceFailure);
            }
            throw ambiguous;
        }
        return repository.complete(key, outcome);
    }

    public Optional<PostgresOrderRepository.Order> get(String accountId, String clientOrderId) throws SQLException {
        return repository.get(new SubmissionKey(accountId, clientOrderId));
    }

    /** Recovers expired reservations and queries an uncertain submission without creating an order. */
    public Optional<PostgresOrderRepository.Order> reconcile(String accountId, String clientOrderId)
            throws SQLException, InterruptedException {
        var key = new SubmissionKey(accountId, clientOrderId);
        var current = repository.recoverExpired(key);
        if (current.isEmpty() || current.get().status() != PostgresOrderRepository.Status.UNKNOWN) return current;
        BrokerAdapter.Evidence evidence;
        try {
            evidence = Objects.requireNonNull(broker.reconcile(key));
        } catch (IOException unavailable) {
            return repository.get(key);
        }
        // A process paused before dispatch could still resume, so absence cannot close durable ambiguity.
        if (evidence instanceof BrokerAdapter.Absent || evidence instanceof BrokerAdapter.Inconclusive) {
            return repository.get(key);
        }
        return Optional.of(repository.applyEvidence(key, evidence));
    }
}
