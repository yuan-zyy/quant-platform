# Sprint 5 order submission and reconciliation

`OrderManagementService` uses the dependency-free `BrokerAdapter` interface. The service reserves an account and client order ID before calling the adapter. Repeating the same order returns its current state. Reusing the ID with different order parameters fails before invoking the adapter. This protects against concurrent duplicate submissions within one process.

An adapter can acknowledge an order, reject it or report that the outcome is unknown. Transport errors also leave the outcome unknown. Resolve that state through `reconcile`, which asks for evidence about the original account and order ID. Inconclusive evidence keeps the order in `UNKNOWN`. An authoritative absence closes it as `NOT_FOUND`. Reconciliation never sends a new submission.

Pass a `PreTradeRiskPolicy` to the service to enforce configured per-account, per-instrument quantity and limit-price notional caps before calling the adapter. Unknown accounts and instruments are denied. A rejected order retains its idempotency key and decision. These checks apply to each order independently. Aggregate exposure, buying-power reservations and market-specific live trading controls remain separate work.

The service currently keeps order state in memory. Test adapters simulate uncertain and conflicting responses. Durable order state, reconciliation scheduling and a validated external broker adapter remain separate work. Real broker access requires explicit authorization.
