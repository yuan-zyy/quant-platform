# Python worker protocol v1

The Java host and a Python strategy process exchange one UTF-8 JSON object per LF-terminated line. The payload limit is 1 MiB, excluding LF. The host sends one request and waits for its correlated response before sending the next. Closing stdin signals normal end of input. Stdout carries protocol messages, and diagnostic output goes to stderr.

Request example

```json
{"version":1,"requestId":"42","event":{"instrument":{"symbol":"600000","exchange":"SSE"},"date":"2026-11-13","eventTime":"2026-11-13T07:00:00Z","availableTime":"2026-11-13T07:00:01Z","processingTime":"2026-11-13T07:00:01Z","open":"8.10","high":"8.30","low":"8.00","close":"8.20","volume":"1000000"},"portfolio":{"cash":"10000.00","positions":[]}}
```

Success response

```json
{"version":1,"requestId":"42","orders":[{"instrument":{"symbol":"600000","exchange":"SSE"},"side":"BUY","quantity":"100","limit":"8.20"}]}
```

Error response

```json
{"version":1,"requestId":"42","error":{"code":"STRATEGY_ERROR","message":"strategy callback failed"}}
```

Each instrument has nonblank string fields `symbol` and `exchange`. Each position has an `instrument` and a positive `quantity`. Positions are unique by instrument and the Java encoder sorts by exchange, then symbol. Orders have side `BUY` or `SELL` and a positive limit. An empty `orders` array means the strategy submits no orders for the event.

Prices and cash use plain decimal strings of at most 128 characters, with ASCII digits, an optional sign and an optional fractional part. Volume and quantity strings use unsigned ASCII integers in signed 64-bit range, with no leading zeros except for zero itself. Cash and volume are nonnegative. Trade date uses `YYYY-MM-DD`. Timestamps use ISO 8601 UTC, with nanosecond precision when supplied, and satisfy `eventTime <= availableTime <= processingTime`. Dates and timestamps support years 0001–9999.

The version is the JSON integer `1` and `requestId` is a nonblank string. Responses must echo the request ID and have exactly one of `orders` and `error`. Extra fields, duplicate keys and additional values after a JSON object are invalid. The worker uses `INVALID_REQUEST`, `STRATEGY_ERROR` and `INVALID_RESULT` error codes. A request with no usable correlation ID may terminate the worker without a response.

This contract is for internal process I/O. The production protobuf/gRPC contract remains future work.
