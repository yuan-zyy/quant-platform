# Sprint 2 Python strategy execution

The Java backtest engine can invoke a trusted Python strategy in a dedicated subprocess. `PythonBacktestStrategy` adapts the existing daily-close strategy callback to a versioned UTF-8 JSON-lines protocol. The request carries the published bar, event/availability/processing times and a portfolio snapshot. The Python response carries order intents or a structured error. Java still applies its trading rules, fills and accounting.

## Run locally

Use Java 21 and Python 3.12. From the repository root:

```sh
mvn -B -ntp -Dquant.python=python clean verify
cd python-sdk
python -m unittest discover -s tests
```

The Java integration test launches `python -m quant_strategy quant_strategy.examples:buy_once` from `python-sdk`. Use `-Dquant.python=/path/to/python` to select a different interpreter. In application code, start a `StrategyProcess` with the configured working directory and request timeout, wrap it in `PythonBacktestStrategy` and pass the adapter to `BacktestEngine.run`. Use one process per backtest and close it with try-with-resources. The Python process may keep strategy state between callbacks in that backtest.

The worker aborts a failed request. The Java caller can cancel an in-flight exchange, and a timeout or broken protocol terminates the worker session. Replay results are returned only after successful completion. Stdout carries protocol frames. The local launcher discards stderr to avoid unbounded output and pipe backpressure.

## Execution boundary

This subprocess mode is for trusted local strategy code. It runs with the operating-system access of its launcher. Before accepting tenant-uploaded code, provision an isolated execution environment per job with a restricted identity, explicit file and network access, and CPU, memory, process and wall-clock limits. The scheduler must be able to terminate that environment as a unit, including descendants. Keep market data, artifacts and diagnostics scoped to the tenant and job.

The versioned JSON-lines contract connects a backtest worker to its local Python process. Protobuf/gRPC for the remote execution boundary remains planned. Its compatibility and retry semantics need to account for stateful strategy callbacks.
