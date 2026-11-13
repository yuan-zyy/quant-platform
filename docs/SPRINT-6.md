# Local backtest load verification

Start the [Java demo server](CONSOLE.md) with an explicit demo token. In a second terminal, run Node.js 22 or later and set `QUANT_DEMO_TOKEN` to the same token.

```sh
QUANT_LOAD_URL=http://127.0.0.1:8080 QUANT_LOAD_JOBS=20 QUANT_LOAD_CONCURRENCY=4 node tools/load/backtest-load.mjs
```

The harness submits `demo-buy-hold`, polls each accepted task, and checks the completed metrics. The JSON report includes HTTP status counts, task success and failure counts, queue saturation and retries, and p50/p95/p99 elapsed milliseconds per task, including retries. Only HTTP 503 during submission is retried. It exits with an error status if any task fails. Use `QUANT_LOAD_SCENARIO`, `QUANT_LOAD_RETRIES`, `QUANT_LOAD_POLL_MS`, `QUANT_LOAD_REQUEST_TIMEOUT_MS` and `QUANT_LOAD_JOB_TIMEOUT_MS` to vary the test.

The harness accepts only loopback HTTP origins and refuses redirects. CI runs a 12-task workload with concurrency 4 against the Java demo. Those measurements describe the small demo run, with its in-memory scheduler and fixed replay data. Production capacity and recovery require separate measurements.
