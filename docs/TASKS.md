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
- [ ] Market data ingestion and ClickHouse schema
- [ ] Factor metadata, versions, PIT snapshots and AST query planner

## Sprint 4 — Platform and paper trading
- [ ] Tenant-aware API, task scheduler, Vue console and PaperBroker

## Sprint 5 — Live trading readiness
- [ ] OMS/RMS, idempotency, UNKNOWN state recovery and authorized broker adapter

## Sprint 6 — Production hardening
- [ ] Security, load tests, HA/DR and recovery drills
