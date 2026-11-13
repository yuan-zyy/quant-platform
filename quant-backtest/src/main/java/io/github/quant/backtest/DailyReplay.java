package io.github.quant.backtest;

import io.github.quant.domain.Instrument;
import io.github.quant.domain.TradingRules;
import io.github.quant.event.EventLoop;
import io.github.quant.event.TimedEvent;
import io.github.quant.event.VirtualClock;
import io.github.quant.market.DataFeed;
import io.github.quant.market.DataFeed.Close;
import io.github.quant.market.DataFeed.Open;
import io.github.quant.oms.LimitOrder;
import io.github.quant.portfolio.Portfolio;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DailyReplay {
    private record Mark(Instant time, BigDecimal price) {}
    private record SessionKey(Instrument instrument, LocalDate date, boolean opening) {}

    private static final Comparator<DataFeed.Observation> CANONICAL_ORDER = Comparator
            .comparing(DataFeed.Observation::eventTime)
            .thenComparing(event -> event.instrument().exchange())
            .thenComparing(event -> event.instrument().symbol())
            .thenComparing(DataFeed.Observation::date)
            .thenComparing(event -> event instanceof Close);

    private final Map<Instrument, TradingRules> rules;
    private final BacktestEngine.Strategy strategy;
    private final Portfolio portfolio;
    private final MatchingEngine matching;
    private final VirtualClock clock = new VirtualClock(Instant.MIN);
    private final List<LimitOrder> orders = new ArrayList<>();
    private final List<LimitOrder> activeOrders = new ArrayList<>();
    private final List<BacktestEngine.Fill> fills = new ArrayList<>();
    private final List<PerformanceMetrics.EquityPoint> equity = new ArrayList<>();
    private final Map<Instrument, Mark> marks = new HashMap<>();
    private final Map<Instrument, LocalDate> lastOpening = new HashMap<>();

    DailyReplay(BigDecimal startingCash, Map<Instrument, TradingRules> rules, ExecutionCosts costs,
                BacktestEngine.Strategy strategy) {
        this.rules = Map.copyOf(rules);
        this.strategy = Objects.requireNonNull(strategy);
        portfolio = new Portfolio(startingCash);
        matching = new MatchingEngine(Objects.requireNonNull(costs));
    }

    BacktestEngine.ReplayResult run(DataFeed feed) {
        var observations = new ArrayList<DataFeed.Observation>();
        feed.replay(event -> observations.add(Objects.requireNonNull(event)));
        observations.sort(CANONICAL_ORDER);
        var sessions = new HashSet<SessionKey>();
        var loop = new EventLoop<DataFeed.Observation>();
        long sequence = 0;
        for (var event : observations) {
            boolean opening = event instanceof Open;
            if (!rules.containsKey(event.instrument())) {
                throw new IllegalArgumentException("missing trading rules for " + event.instrument());
            }
            if (!sessions.add(new SessionKey(event.instrument(), event.date(), opening))) {
                throw new IllegalArgumentException("duplicate observation for " + event.instrument() + " " + event.date());
            }
            loop.publish(new TimedEvent<>(event.eventTime(), event.availableTime(), opening ? 0 : 1,
                    sequence++, event));
        }
        BigDecimal initial = portfolio.snapshot().cash();
        loop.drain(this::process);
        activeOrders.forEach(order -> order.cancel("end of replay"));
        return new BacktestEngine.ReplayResult(portfolio.snapshot(),
                orders.stream().map(LimitOrder::snapshot).toList(), fills, equity,
                PerformanceMetrics.calculate(initial, equity, fills));
    }

    private void process(TimedEvent<DataFeed.Observation> event) {
        clock.advanceTo(event.availableTime());
        switch (event.payload()) {
            case Open open -> {
                LocalDate previous = lastOpening.put(open.instrument(), open.date());
                if (previous != null && !open.date().isAfter(previous)) {
                    throw new IllegalArgumentException("opening sessions must become available in date order");
                }
                mark(open.instrument(), open.eventTime(), open.price());
                matching.match(open, rules.get(open.instrument()), activeOrders, portfolio, fills, clock.now());
                activeOrders.removeIf(order -> !order.active());
                valuePortfolio();
            }
            case Close close -> {
                mark(close.instrument(), close.eventTime(), close.bar().close());
                var account = valuePortfolio();
                var context = new BacktestEngine.Context(clock.now(), account);
                List.copyOf(strategy.onClose(close, context)).forEach(this::submit);
            }
        }
    }

    private void mark(Instrument instrument, Instant eventTime, BigDecimal price) {
        Mark old = marks.get(instrument);
        if (old == null || eventTime.isAfter(old.time())) marks.put(instrument, new Mark(eventTime, price));
    }

    private Portfolio.Snapshot valuePortfolio() {
        var snapshot = portfolio.snapshot();
        BigDecimal value = snapshot.cash();
        for (var position : snapshot.positions().entrySet()) {
            value = value.add(marks.get(position.getKey()).price().multiply(BigDecimal.valueOf(position.getValue())));
        }
        equity.add(new PerformanceMetrics.EquityPoint(clock.now(), value));
        return snapshot;
    }

    private void submit(LimitOrder.Request request) {
        var order = new LimitOrder(orders.size() + 1L, request, clock.now());
        orders.add(order);
        TradingRules instrumentRules = rules.get(request.instrument());
        if (instrumentRules == null) {
            order.reject("missing trading rules");
        } else if (request.side() == LimitOrder.Side.BUY && request.quantity() % instrumentRules.buyLotSize() != 0) {
            order.reject("quantity is not a valid buy lot");
        } else {
            activeOrders.add(order);
        }
    }
}
