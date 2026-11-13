package io.github.quant.worker.rpc;

import io.github.quant.worker.StrategyTransport;
import io.github.quant.worker.rpc.v1.StrategyRequest;
import io.github.quant.worker.rpc.v1.StrategyResponse;
import io.github.quant.worker.rpc.v1.StrategyWorkerGrpc;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.quant.worker.rpc.RemoteStrategyWorker.VERSION;

/** One remote stream per backtest. A failed exchange permanently closes the stream. */
public final class GrpcStrategyTransport implements StrategyTransport {
    private final Object lock = new Object();
    private final long requestTimeoutNanos;
    private final ClientCallStreamObserver<StrategyRequest> call;
    private CompletableFuture<String> pending;
    private long sequence;
    private boolean closed;

    public GrpcStrategyTransport(Channel channel, Duration sessionTimeout, Duration requestTimeout) {
        requestTimeoutNanos = requestTimeout.toNanos();
        long sessionNanos = sessionTimeout.toNanos();
        if (requestTimeoutNanos <= 0 || sessionNanos <= 0) {
            throw new IllegalArgumentException("session and request timeouts must be positive");
        }
        var listener = new Responses();
        StrategyWorkerGrpc.newStub(channel).withDeadlineAfter(sessionNanos, TimeUnit.NANOSECONDS)
                .withMaxInboundMessageSize(RemoteStrategyWorker.MAX_MESSAGE_BYTES).run(listener);
        call = listener.requestStream;
    }

    @Override
    public String exchange(String request) throws IOException, TimeoutException, InterruptedException {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(request)
                || !RemoteStrategyWorker.validLine(request, request.getBytes(StandardCharsets.UTF_8).length)) {
            throw new IllegalArgumentException("invalid strategy request frame");
        }
        CompletableFuture<String> result;
        synchronized (lock) {
            if (closed) throw new IOException("remote worker stream is closed");
            if (pending != null) throw new IllegalStateException("strategy request already in flight");
            result = new CompletableFuture<>();
            pending = result;
            try {
                call.onNext(StrategyRequest.newBuilder().setProtocolVersion(VERSION).setSequence(++sequence)
                        .setStrategyJson(request).build());
            } catch (RuntimeException failure) {
                cancel();
                throw new IOException("remote worker send failed", failure);
            }
        }

        try {
            return result.get(requestTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | InterruptedException failure) {
            cancel();
            throw failure;
        } catch (ExecutionException failure) {
            cancel();
            if (Status.fromThrowable(failure.getCause()).getCode() == Status.Code.DEADLINE_EXCEEDED) {
                throw new TimeoutException("remote worker deadline exceeded");
            }
            throw new IOException("remote worker exchange failed", failure.getCause());
        } finally {
            synchronized (lock) {
                pending = null;
            }
        }
    }

    @Override
    public void cancel() {
        synchronized (lock) {
            if (closed) return;
            terminate(new IOException("remote worker stream canceled"));
            call.cancel("strategy canceled", null);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            if (pending == null) {
                terminate(null);
                call.onCompleted();
            } else {
                cancel();
            }
        }
    }

    private void terminate(Throwable cause) {
        closed = true;
        if (pending != null) pending.completeExceptionally(cause);
    }

    private final class Responses implements ClientResponseObserver<StrategyRequest, StrategyResponse> {
        private ClientCallStreamObserver<StrategyRequest> requestStream;

        @Override
        public void beforeStart(ClientCallStreamObserver<StrategyRequest> stream) {
            requestStream = stream;
        }

        @Override
        public void onNext(StrategyResponse response) {
            synchronized (lock) {
                if (closed) return;
                if (pending == null || pending.isDone() || response.getProtocolVersion() != VERSION
                        || response.getSequence() != sequence
                        || !RemoteStrategyWorker.validLine(response.getStrategyJson(),
                                response.getStrategyJsonBytes().size())) {
                    terminate(new IOException("invalid remote worker response frame"));
                    requestStream.cancel("invalid strategy response", null);
                } else {
                    pending.complete(response.getStrategyJson());
                }
            }
        }

        @Override
        public void onError(Throwable failure) {
            synchronized (lock) {
                terminate(failure);
            }
        }

        @Override
        public void onCompleted() {
            synchronized (lock) {
                terminate(new IOException("remote worker stream completed"));
            }
        }
    }
}
