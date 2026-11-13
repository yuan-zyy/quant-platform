# Sprint 5 order submission and reconciliation

`OrderManagementService` uses the dependency-free `BrokerAdapter` interface. The service reserves an account and client order ID before calling the adapter. Repeating the same order returns its current state. Reusing the ID with different order parameters fails before invoking the adapter. This protects against concurrent duplicate submissions within one process.

An adapter can acknowledge an order, reject it or report that the outcome is unknown. Transport errors also leave the outcome unknown. Resolve that state through `reconcile`, which asks for evidence about the original account and order ID. Inconclusive evidence keeps the order in `UNKNOWN`. An authoritative absence closes it as `NOT_FOUND`. Reconciliation never sends a new submission.

Pass a `PreTradeRiskPolicy` to the service to enforce configured per-account, per-instrument quantity and limit-price notional caps before calling the adapter. Unknown accounts and instruments are denied. A rejected order retains its idempotency key and decision. These checks apply to each order independently. Aggregate exposure, buying-power reservations and market-specific live trading controls remain separate work.

The in-memory service is available for deterministic simulation. For a restartable simulation, apply [V002__broker_orders.sql](../quant-server/src/main/resources/db/migration/V002__broker_orders.sql) and construct `PostgresOrderManagementService` with its repository, adapter, risk policy and a submission deadline. It persists the account and order key before dispatch and uses conditional updates for outcomes and reconciliation. A repeated or expired reservation never triggers another submission. Reconciliation is explicit and resolves `UNKNOWN` only on definite acceptance or rejection. An absent lookup leaves the durable order in `UNKNOWN` because an interrupted process might have paused before dispatch.

Scheduled reconciliation and a validated external broker adapter remain separate work. Real broker access requires explicit authorization.
