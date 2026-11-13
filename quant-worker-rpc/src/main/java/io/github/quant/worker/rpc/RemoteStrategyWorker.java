package io.github.quant.worker.rpc;

import io.github.quant.strategy.api.StrategyProtocol;
import io.github.quant.worker.StrategyTransport;
import io.github.quant.worker.rpc.v1.StrategyRequest;
import io.github.quant.worker.rpc.v1.StrategyResponse;
import io.github.quant.worker.rpc.v1.StrategyWorkerGrpc;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Internal gRPC endpoint whose strategy launcher is selected by the server. */
public final class RemoteStrategyWorker extends StrategyWorkerGrpc.StrategyWorkerImplBase {
    private static final System.Logger LOG = System.getLogger(RemoteStrategyWorker.class.getName());
    public static final int VERSION = 1;
    public static final int MAX_MESSAGE_BYTES = StrategyProtocol.MAX_LINE_BYTES + 128;

    @FunctionalInterface
    public interface Launcher {
        StrategyTransport start() throws IOException, InterruptedException;
    }

    private final Launcher launcher;

    public RemoteStrategyWorker(Launcher launcher) {
        this.launcher = Objects.requireNonNull(launcher);
    }

    @Override
    public StreamObserver<StrategyRequest> run(StreamObserver<StrategyResponse> observer) {
        var responses = (ServerCallStreamObserver<StrategyResponse>) observer;
        responses.disableAutoRequest();
        var session = new Session(responses, launcher);
        var context = Context.current();
        Context.CancellationListener listener = ignored -> session.close();
        context.addListener(listener, Runnable::run);
        responses.setOnCancelHandler(session::close);
        responses.setOnCloseHandler(() -> {
            context.removeListener(listener);
            session.close();
        });
        responses.request(1);
        return session;
    }

    static boolean validLine(String json, int utf8Bytes) {
        return !json.isEmpty() && utf8Bytes <= StrategyProtocol.MAX_LINE_BYTES
                && json.indexOf('\n') < 0 && json.indexOf('\r') < 0;
    }

    private static final class Session implements StreamObserver<StrategyRequest> {
        private final ServerCallStreamObserver<StrategyResponse> responses;
        private final Launcher launcher;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<StrategyTransport> worker = new AtomicReference<>();
        private long sequence = 1;

        Session(ServerCallStreamObserver<StrategyResponse> responses, Launcher launcher) {
            this.responses = responses;
            this.launcher = launcher;
        }

        @Override
        public void onNext(StrategyRequest request) {
            if (closed.get()) return;
            if (request.getProtocolVersion() != VERSION || request.getSequence() != sequence
                    || !validLine(request.getStrategyJson(), request.getStrategyJsonBytes().size())) {
                fail(Status.INVALID_ARGUMENT.withDescription("invalid strategy request frame"));
                return;
            }

            try {
                var process = worker.get();
                if (process == null) {
                    process = launcher.start();
                    worker.set(process);
                    if (closed.get()) {
                        stopWorker();
                        return;
                    }
                }

                String reply = process.exchange(request.getStrategyJson());
                int size = reply.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (!validLine(reply, size)) {
                    fail(Status.INTERNAL.withDescription("invalid worker response frame"));
                    return;
                }

                if (!closed.get()) {
                    responses.onNext(StrategyResponse.newBuilder().setProtocolVersion(VERSION)
                            .setSequence(sequence++).setStrategyJson(reply).build());
                    responses.request(1);
                }
            } catch (TimeoutException timeout) {
                fail(Status.DEADLINE_EXCEEDED.withDescription("worker request timed out"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail(Status.CANCELLED.withDescription("worker request interrupted"));
            } catch (IOException | RuntimeException failure) {
                fail(Status.INTERNAL.withDescription("worker exchange failed"));
            }
        }

        @Override
        public void onError(Throwable failure) {
            close();
        }

        @Override
        public void onCompleted() {
            if (close()) responses.onCompleted();
        }

        private void fail(Status status) {
            if (close()) responses.onError(status.asRuntimeException());
        }

        boolean close() {
            if (!closed.compareAndSet(false, true)) return false;
            stopWorker();
            return true;
        }

        private void stopWorker() {
            var process = worker.getAndSet(null);
            if (process != null) {
                try {
                    process.close();
                } catch (RuntimeException cleanupFailure) {
                    LOG.log(System.Logger.Level.WARNING, "Strategy worker cleanup failed");
                }
            }
        }
    }
}
