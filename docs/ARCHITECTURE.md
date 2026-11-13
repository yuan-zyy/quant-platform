# Architecture baseline

- Control plane and execution plane are separate.
- Java 21 owns domain state, order lifecycle, event processing and risk checks; Python is the user strategy language.
- Backtest, paper and live share strategy contracts, not execution assumptions.
- PostgreSQL stores transactional business state; ClickHouse stores market/factor analytics; object storage stores immutable artifacts.
- User code runs in restricted sandboxes, never inside the API JVM.
- Version strategy, market data, factors, market rules, fees and slippage for reproducibility and point-in-time correctness.
- Distinguish eventTime, availableTime and processingTime to prevent look-ahead bias.

## Modules
quant-common, quant-domain, quant-event, quant-market, quant-portfolio, quant-backtest, quant-strategy-api, quant-oms, quant-risk, quant-broker-spi, quant-server, quant-worker.

## Non-goals in Sprint 0
No live broker connection, no real orders, no unverified million-user capacity claim.
