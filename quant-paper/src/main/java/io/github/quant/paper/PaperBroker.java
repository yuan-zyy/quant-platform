package io.github.quant.paper;

import io.github.quant.backtest.BacktestEngine;
import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.backtest.MatchingEngine;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.DataFeed;
import io.github.quant.oms.LimitOrder;
import io.github.quant.portfolio.Portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Single-account, in-memory simulator with explicit processing time and no external broker. */
public final class PaperBroker {
    private record Session(Instrument instrument, LocalDate date) {}

    private final Map<Instrument, TradingRules> rules;
    private final Portfolio portfolio;
    private final MatchingEngine matching;
    private final Map<String, LimitOrder> orders = new LinkedHashMap<>();
    private final Map<Session, DataFeed.Open> openings = new HashMap<>();
    private final Map<Instrument, LocalDate> lastOpening = new HashMap<>();
    private Instant clock = Instant.MIN;

    public PaperBroker(BigDecimal cash, Map<Instrument, TradingRules> rules, ExecutionCosts costs) {
        this.rules = Map.copyOf(rules);
        portfolio = new Portfolio(cash);
        matching = new MatchingEngine(Objects.requireNonNull(costs));
    }

    public synchronized LimitOrder.Snapshot submit(String clientOrderId, LimitOrder.Request request,
                                                    Instant processingTime) {
        requireOrderId(clientOrderId);
        Objects.requireNonNull(request);
        Objects.requireNonNull(processingTime);
        LimitOrder existing = orders.get(clientOrderId);
        if (existing != null) {
            if (!existing.request().equals(request) || !existing.submittedAt().equals(processingTime)) {
                throw new IllegalArgumentException("client order ID conflicts with an existing submission");
            }
            return existing.snapshot();
        }
        requireCurrentTime(processingTime);
        var order = new LimitOrder(orders.size() + 1L, request, processingTime);
        TradingRules instrumentRules = rules.get(request.instrument());
        if (instrumentRules == null) {
            order.reject("missing trading rules");
        } else if (request.side() == LimitOrder.Side.BUY && request.quantity() % instrumentRules.buyLotSize() != 0) {
            order.reject("quantity is not a valid buy lot");
        }
        orders.put(clientOrderId, order);
        clock = processingTime;
        return order.snapshot();
    }

    public synchronized Optional<LimitOrder.Snapshot> cancel(String clientOrderId, Instant processingTime) {
        requireOrderId(clientOrderId);
        Objects.requireNonNull(processingTime);
        LimitOrder order = orders.get(clientOrderId);
        if (order == null) return Optional.empty();
        if (order.active()) {
            requireCurrentTime(processingTime);
            order.cancel("canceled by client");
            clock = processingTime;
        }
        return Optional.of(order.snapshot());
    }

    public synchronized Optional<LimitOrder.Snapshot> order(String clientOrderId) {
        requireOrderId(clientOrderId);
        return Optional.ofNullable(orders.get(clientOrderId)).map(LimitOrder::snapshot);
    }

    public synchronized Portfolio.Snapshot portfolio() {
        return portfolio.snapshot();
    }

    public synchronized List<BacktestEngine.Fill> onOpen(DataFeed.Open opening) {
        var session = new Session(opening.instrument(), opening.date());
        var previous = openings.get(session);
        if (previous != null) {
            if (!previous.equals(opening)) throw new IllegalArgumentException("conflicting opening publication");
            return List.of();
        }
        var instrumentRules = rules.get(opening.instrument());
        if (instrumentRules == null) throw new IllegalArgumentException("missing trading rules for opening");
        requireCurrentTime(opening.availableTime());
        var latest = lastOpening.get(opening.instrument());
        if (latest != null && !opening.date().isAfter(latest)) {
            throw new IllegalArgumentException("opening sessions must become available in date order");
        }

        var fills = new ArrayList<BacktestEngine.Fill>();
        matching.match(opening, instrumentRules, List.copyOf(orders.values()), portfolio, fills, opening.availableTime());
        openings.put(session, opening);
        lastOpening.put(opening.instrument(), opening.date());
        clock = opening.availableTime();
        return List.copyOf(fills);
    }

    private static void requireOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("client order ID is required");
        }
    }

    private void requireCurrentTime(Instant processingTime) {
        if (processingTime.isBefore(clock)) throw new IllegalArgumentException("processing time moved backwards");
    }
}
