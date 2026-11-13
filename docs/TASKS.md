# Delivery plan

## Sprint 0 — Engineering baseline
- [x] Repository access confirmed
- [x] Maven Java 21 multi-module skeleton
- [x] Architecture, task and decision records
- [x] CI build/test workflow

## Sprint 1 — Core and daily backtest
- [x] Instrument, Bar, order, account and position models
- [x] Deterministic event contracts and virtual clock
- [x] Historical DataFeed and order state machine
- [x] Limit matching, fees, portfolio valuation and metrics
- [x] Look-ahead and reproducibility tests

## Sprint 2 — Python SDK and runtime
- [x] Versioned local strategy contract and Python SDK
- [x] Protobuf/gRPC contract for remote workers
- [x] Trusted local subprocess lifecycle, timeout and cancellation
- [x] Linux strategy container with job-level resource isolation
- [ ] Tenant-aware scheduling, image intake and execution-host provisioning

## Sprint 3 — Data and factors
- [x] Historical CSV import, versioned market snapshots and backtest replay feed
- [x] ClickHouse market observation schema design
- [x] Persistent ClickHouse batch journal adapter for import and replay
- [x] ClickHouse analytical observation view and bounded Java snapshot queries
- [ ] ClickHouse production deployment and materialized analytical storage
- [x] Factor metadata, versions and in-memory PIT snapshots
- [x] Typed factor AST planner and point-in-time expression evaluation
- [x] Persistent ClickHouse factor definitions, values and planner read views

## Sprint 4 — Platform and paper trading
- [x] Tenant-scoped backtest task API, in-memory scheduling and cancellation
- [x] Deterministic in-memory PaperBroker and command/event deduplication
- [x] Local Vue backtest console for submission, task metrics and cancellation
- [x] PostgreSQL task state, idempotent submissions and leased recovery
- [x] Restartable local paper accounts with a command journal
- [ ] Deployment authentication and production paper account storage

## Sprint 5 — Live trading readiness
- [x] Simulated OMS with order idempotency, UNKNOWN reconciliation and per-order risk admission
- [x] Durable simulated OMS reservations and explicit reconciliation
- [x] Bounded scheduled reconciliation for simulated orders
- [ ] Authorized external broker adapter

## Sprint 6 — Production hardening
- [x] Reproducible loopback backtest load smoke test and latency reporting
- [x] PostgreSQL backup and restore drill for task and order recovery
- [x] Loopback HTTP handler admission and exchange deadline
- [ ] Security, production load tests and HA/DR deployment validation
