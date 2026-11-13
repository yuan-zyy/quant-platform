# Delivery plan

## Sprint 0 — Engineering baseline
- [x] Repository access confirmed
- [ ] Maven Java 21 multi-module skeleton
- [ ] Architecture, task and decision records
- [ ] CI build/test workflow

## Sprint 1 — Core and daily backtest
- [ ] Instrument, Bar, order, account and position models
- [ ] Deterministic event contracts and virtual clock
- [ ] Historical DataFeed and order state machine
- [ ] Limit matching, fees, portfolio valuation and metrics
- [ ] Look-ahead and reproducibility tests

## Sprint 2 — Python SDK and runtime
- [ ] Stable strategy API and protobuf/gRPC contract
- [ ] Worker lifecycle, isolation, timeout and cancellation

## Sprint 3 — Data and factors
- [ ] Market data ingestion and ClickHouse schema
- [ ] Factor metadata, versions, PIT snapshots and AST query planner

## Sprint 4 — Platform and paper trading
- [ ] Tenant-aware API, task scheduler, Vue console and PaperBroker

## Sprint 5 — Live trading readiness
- [ ] OMS/RMS, idempotency, UNKNOWN state recovery and authorized broker adapter

## Sprint 6 — Production hardening
- [ ] Security, load tests, HA/DR and recovery drills
