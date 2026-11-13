package io.github.quant.server.backtest;

import io.github.quant.backtest.BacktestEngine;
import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.DataFeed;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** A fixed catalog of operator-registered scenarios, indexed by trusted tenant identity. */
public final class BacktestScenarioCatalog {
    public record Key(String tenantId, String scenarioId) {
        public Key {
            if (tenantId.isBlank() || scenarioId.isBlank()) {
                throw new IllegalArgumentException("tenant and scenario IDs must be nonblank");
            }
        }
    }

    public record Scenario(DataFeed feed, BigDecimal startingCash, Map<Instrument, TradingRules> rules,
                           ExecutionCosts costs, Supplier<BacktestEngine.Strategy> strategyFactory) {
        public Scenario {
            Objects.requireNonNull(feed);
            if (startingCash.signum() <= 0) throw new IllegalArgumentException("starting cash must be positive");
            rules = Map.copyOf(rules);
            Objects.requireNonNull(costs);
            Objects.requireNonNull(strategyFactory);
        }
    }

    private final Map<Key, Scenario> scenarios;

    public BacktestScenarioCatalog(Map<Key, Scenario> scenarios) {
        this.scenarios = Map.copyOf(scenarios);
    }

    public Optional<BacktestTaskScheduler.BacktestJob> resolve(String tenantId, String scenarioId) {
        return Optional.ofNullable(scenarios.get(new Key(tenantId, scenarioId))).map(scenario -> () -> {
            var strategy = Objects.requireNonNull(scenario.strategyFactory().get());
            try (AutoCloseable resource = strategy instanceof AutoCloseable closeable ? closeable : null) {
                return new BacktestEngine().run(scenario.feed(), scenario.startingCash(), scenario.rules(),
                        scenario.costs(), strategy);
            }
        });
    }
}
