# Local backtest load verification

Start the [Java demo server](CONSOLE.md) with an explicit demo token. In a second terminal, run Node.js 22 or later and set `QUANT_DEMO_TOKEN` to the same token.

```sh
QUANT_LOAD_URL=http://127.0.0.1:8080 QUANT_LOAD_JOBS=20 QUANT_LOAD_CONCURRENCY=4 node tools/load/backtest-load.mjs
```

The harness submits `demo-buy-hold`, polls each accepted task, and checks the completed metrics. The JSON report includes HTTP status counts, task success and failure counts, queue saturation and retries, and p50/p95/p99 elapsed milliseconds per task, including retries. Only HTTP 503 during submission is retried. It exits with an error status if any task fails. Use `QUANT_LOAD_SCENARIO`, `QUANT_LOAD_RETRIES`, `QUANT_LOAD_POLL_MS`, `QUANT_LOAD_REQUEST_TIMEOUT_MS` and `QUANT_LOAD_JOB_TIMEOUT_MS` to vary the test.

The harness accepts only loopback HTTP origins and refuses redirects. CI runs a 12-task workload with concurrency 4 against the Java demo. Those measurements describe the small demo run, with its in-memory scheduler and fixed replay data. Production capacity and recovery require separate measurements.

## PostgreSQL restore drill

CI uses a temporary PostgreSQL container. The drill creates `quant_recovery_source` and `quant_recovery_target` inside it, applies migrations to the source, seeds simulated tasks and orders, and streams a custom-format dump into the target. The target is restored in a single transaction. SQL checks validate state and constraints before an optional callback runs. Cleanup drops only the two databases created by the drill.

Run `bash tools/recovery/postgres-restore.sh DISPOSABLE_POSTGRES_CONTAINER` with `PGUSER` and `PGPASSWORD` set for the test container. An optional command after the container name receives the restored database name in `QUANT_RECOVERY_RESTORED_DB` and can test the restored state before cleanup. CI uses that hook to run the Java workers against the restored database. It verifies queued tasks resume, expired leases are reclaimed, stale workers cannot publish results, and due uncertain orders reconcile without a new submission.

## Backtest HTTP admission

The loopback demo admits at most eight concurrent handler calls. Excess requests receive a generic `503` with `Retry-After: 1`. The request deadline closes the exchange after five seconds. This deadline governs the HTTP connection. An admitted task runs independently and is canceled through the authenticated task endpoint. The existing submit handler still limits the scenario ID to 128 bytes. Connection and header limits belong at the deployment ingress.

The demo provides `GET /health/live` and `GET /health/ready` on the loopback listener. Liveness confirms the HTTP listener responds. Readiness checks the demo request executor and returns a generic `503` on failure. The reusable readiness handler accepts one injected probe at a time and times out the result. Probes should honor interruption and configure their own I/O timeouts. The demo has no external database dependency, so the readiness endpoint does not check database health.

`GET /metrics` on the loopback listener exposes process-local Prometheus metrics for backtest requests. It reports total requests, requests in flight, maximum handler duration and fixed response status classes, including requests that ended without a response. The metrics exclude health checks and scrape requests. Output contains no tenant IDs, credentials, paths or query parameters. A reverse proxy should keep this route private because the handler checks the direct socket peer.

The demo retains its latest 256 completed request audit events in memory. Events contain only the UTC time, fixed operation category and fixed outcome category. The audit component exposes an immutable snapshot to its Java owner. The demo provides no audit HTTP endpoint. Persistent audit retention and operator access are deployment decisions.

The in-memory scheduler can cap each tenant's queued and executing tasks. The demo sets the cap to eight. Canceling a queued task frees its slot immediately. A canceled running task retains its slot until the callback exits, even after the API reports it as canceled. PostgreSQL workers do not share this process-local quota.
