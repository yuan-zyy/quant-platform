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

## Execution boundaries

The subprocess mode is for trusted local strategy code and runs with the operating-system access of its launcher. On a Linux execution host, the Docker transport starts one container per strategy job from an approved immutable image. The worker supplies strategy input over stdin. The container runs with a non-root identity, a read-only root filesystem, bounded writable scratch space, network disabled, and CPU, memory, process and wall-clock limits. The launcher removes the container at session end or cancellation. The Docker daemon and its client belong on a dedicated execution host. Restrict access to the daemon and keep strategy images and job data scoped to the tenant.

The versioned JSON Lines contract connects the worker to Python. The [remote worker module](../quant-worker-rpc/README.md) wraps it in a protobuf/gRPC bidirectional stream for remote execution. A stream owns a single stateful strategy session and sends callbacks in sequence. The service creates the session through a launcher supplied by the operator. Use the service behind an authenticated internal boundary. A disconnected stream ends its session. Restart a failed backtest from the beginning if a callback outcome is uncertain, since replaying a callback on a replacement session could produce a different result.

## Verify the Linux container boundary

From the repository root on a Linux host with Docker:

```sh
image=$(docker build --quiet -f quant-worker/sandbox/Dockerfile .)
mvn -B -ntp -Dquant.python=python "-Dquant.sandbox.image=$image" verify
```

GitHub Actions runs the same container tests with an immutable image ID. See the [sandbox runtime documentation](../quant-worker/sandbox/README.md) for worker-host and strategy image requirements. Provisioning, authenticated remote worker deployment, image intake and multi-tenant scheduling are deployment work.
