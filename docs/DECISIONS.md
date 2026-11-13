# Architecture decisions

1. Java 21 + Python: Java owns transaction and domain core; Python owns user strategies and research.
2. Modular monolith first: preserve Maven boundaries; split deployments only for scaling/isolation/team needs.
3. Storage by workload: PostgreSQL for transactions, ClickHouse for analytics, object storage for artifacts, Redis for cache.
4. Shared contracts, separate execution semantics: virtual clock/matching for backtest; real clock/broker adapter for live.
5. Point-in-time data: version market data, factors and trading rules.
6. Live orders: do not assume broker side effects are exactly-once; use idempotency, durable state, UNKNOWN status and reconciliation.
