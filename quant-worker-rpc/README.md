# Remote strategy worker protocol v1

`strategy_worker.proto` defines a bidirectional gRPC call. One stream owns one worker and its strategy state. Requests begin at sequence 1 and advance one at a time. Responses echo the sequence. The payload uses the [local strategy protocol](../quant-strategy-api/README.md), preserving decimal strings and all three timestamps across transports.

Bind `new RemoteStrategyWorker(trustedLauncher)` to an internal gRPC server. Set the server's `maxInboundMessageSize(RemoteStrategyWorker.MAX_MESSAGE_BYTES)`, configure an authenticated transport, and scope the launcher and its resources to an authorized job. The launcher supplies the image, entrypoint and data access. The request frame only carries strategy inputs and a sequence. Use the standard gRPC server executor or a dedicated executor for blocking worker calls.

Construct `GrpcStrategyTransport(channel, sessionTimeout, requestTimeout)` with an authenticated channel and pass it to `PythonBacktestStrategy`. Close the strategy with try-with-resources. The caller owns the channel. The Java adapter verifies response correlation in both the protobuf envelope and the local strategy payload.

A timeout, cancellation or transport failure ends the stream and closes its worker. Re-run the whole replay in a new stream when recovery is needed, since an individual request might already have changed strategy state. Configure the client channel without retry policies for this method.

The in-process gRPC tests verify sequencing, cancellation and status mapping. Run Maven with `-Dquant.python=python` to include the end-to-end test that invokes the Python SDK over gRPC and checks the resulting backtest fill.

Maven selects the protobuf compiler for Windows and Linux x86-64. On other hosts, set the available compiler classifier explicitly with `-Dprotobuf.classifier=<classifier>`.
