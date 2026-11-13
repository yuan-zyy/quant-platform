# Sprint 4 backtest tasks

The Java service runs asynchronous backtests through a bounded in-memory scheduler. `BacktestScenarioCatalog` maps a trusted tenant and scenario ID to an operator-registered replay. A scenario supplies the data feed, trading rules, initial cash, execution costs and a new strategy instance for each task.

Mount `BacktestHttpApi` on a JDK HTTP server and supply an authenticator that derives the tenant ID from verified credentials. The handler uses that ID both when resolving scenarios and when accessing stored tasks. A missing task and a task owned by another tenant both return `404`.

| Method and path | Behavior |
| --- | --- |
| `POST /v1/backtests` | Accept a registered scenario ID as UTF-8 `text/plain`. Return `202` and a task URL in `Location`. |
| `GET /v1/backtests/{id}` | Return the owned task, lifecycle timestamps and metrics when completed. |
| `DELETE /v1/backtests/{id}` | Cancel an active owned task and return its state. Completed states are preserved. |

The scheduler bounds workers, queued tasks and retained history. Completed tasks may be evicted when new tasks arrive. A full queue or unavailable scheduler returns `503`. Cancellation removes queued work or interrupts the running worker. The Java replay checks interruption between events and during loading. Scenario feeds and strategies that perform blocking work must also respond to interruption.

This service stores task state in process memory. Configure authenticated transport, quotas and durable task state before operating it as a production tenant service. The HTTP adapter runs registered scenarios supplied by the operator.

The [local Vue console](CONSOLE.md) exercises this API against a loopback demo server.

## Paper broker

`quant-paper` provides an in-memory simulated brokerage account. Instantiate a `PaperBroker` with cash, instrument rules and execution costs. Submit or cancel a limit order using its client order ID, then feed historical or simulated opening observations to advance matching. The paper broker shares the replay matcher, so lot size, opening liquidity, slippage, fees, cash checks and T+1 eligibility follow the same rules.

Supply command times and opening availability times in chronological order. An opening can fill only orders submitted before its event time. A cancellation processed before a delayed opening arrives takes effect before that observation is matched. Repeating an identical submit or opening does not change the account. A different order or opening payload with the same key is rejected. Check order and portfolio snapshots to read the current account state.

The broker instance represents one simulated account. State and deduplication are process-local. Multi-account routing, persistent order state and production risk controls remain separate work.
