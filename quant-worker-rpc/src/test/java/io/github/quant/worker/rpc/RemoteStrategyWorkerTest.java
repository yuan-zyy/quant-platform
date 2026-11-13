package io.github.quant.worker.rpc;

import io.github.quant.backtest.BacktestEngine;
import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.Bar;
import io.github.quant.market.HistoricalDataFeed;
import io.github.quant.worker.PythonBacktestStrategy;
import io.github.quant.worker.StrategyProcess;
import io.github.quant.worker.StrategyTransport;
import io.github.quant.worker.rpc.v1.StrategyRequest;
import io.github.quant.worker.rpc.v1.StrategyResponse;
import io.github.quant.worker.rpc.v1.StrategyWorkerGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RemoteStrategyWorkerTest {
    private ManagedChannel channel;
    private Server server;

    @AfterEach
    void stop() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void preservesStateAcrossRequestsAndClosesOnCompletion() throws Exception {
        var calls = new AtomicInteger();
        var closed = new CountDownLatch(1);
        start(() -> new FakeTransport(closed) {
            @Override
            public String exchange(String input) {
                return input + calls.incrementAndGet();
            }
        });

        try (var transport = client(Duration.ofSeconds(5))) {
            assertEquals("{}1", transport.exchange("{}"));
            assertEquals("{}2", transport.exchange("{}"));
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void rejectsUnsupportedVersionAndInitialSequenceGapWithoutStartingWorker() throws Exception {
        var launches = new AtomicInteger();
        start(() -> {
            launches.incrementAndGet();
            throw new IOException("unexpected worker launch");
        });
        assertInvalid(2, 1);
        assertInvalid(1, 2);
        assertEquals(0, launches.get());
    }

    @Test
    void mapsWorkerTimeoutAndDiscardsTheFailedStream() throws Exception {
        var closed = new CountDownLatch(1);
        start(() -> new FakeTransport(closed) {
            @Override
            public String exchange(String input) throws TimeoutException {
                throw new TimeoutException("worker details");
            }
        });
        try (var transport = client(Duration.ofSeconds(5))) {
            var failure = assertThrows(TimeoutException.class, () -> transport.exchange("{}"));
            assertEquals("remote worker deadline exceeded", failure.getMessage());
            assertThrows(IOException.class, () -> transport.exchange("{}"));
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void cancelClosesWorkerWhileExchangeIsBlocked() throws Exception {
        var entered = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        start(() -> new FakeTransport(closed) {
            @Override
            public String exchange(String input) throws InterruptedException {
                entered.countDown();
                closed.await(20, TimeUnit.SECONDS);
                return "{}";
            }
        });
        try (var transport = client(Duration.ofSeconds(10))) {
            var outcome = new CompletableFuture<Exception>();
            Thread.ofVirtual().start(() -> {
                try {
                    transport.exchange("{}");
                    outcome.complete(null);
                } catch (Exception failure) {
                    outcome.complete(failure);
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            transport.cancel();
            assertInstanceOf(IOException.class, outcome.get(5, TimeUnit.SECONDS));
            assertTrue(closed.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "quant.python", matches = ".+")
    void executesPythonBacktestOverGrpc() throws Exception {
        var command = List.of(System.getProperty("quant.python"), "-m", "quant_strategy",
                "quant_strategy.examples:buy_once");
        var sdk = Path.of("..", "python-sdk").toAbsolutePath().normalize();
        start(() -> StrategyProcess.start(command, sdk, Duration.ofSeconds(5)));
        var instrument = new Instrument("600000", "SSE");
        var date = LocalDate.of(2026, 1, 5);
        var price = BigDecimal.TEN;
        var first = new Bar(date, price, price, price, price, 1000);
        var second = new Bar(date.plusDays(1), price, price, price, price, 1000);
        var feed = new HistoricalDataFeed(List.of(
                new HistoricalDataFeed.Session(instrument, first, 100, HistoricalDataFeed.closeTime(first)),
                new HistoricalDataFeed.Session(instrument, second, 100, HistoricalDataFeed.closeTime(second))));

        try (var strategy = new PythonBacktestStrategy(client(Duration.ofSeconds(10)))) {
            var result = new BacktestEngine().run(feed, BigDecimal.valueOf(10_000),
                    Map.of(instrument, TradingRules.forType(SecurityType.A_SHARE)), ExecutionCosts.NONE, strategy);
            assertEquals(1, result.fills().size());
            assertEquals(second.date(), result.fills().getFirst().tradeDate());
            assertEquals(Map.of(instrument, 100L), result.portfolio().positions());
        }
    }

    private void start(RemoteStrategyWorker.Launcher launcher) throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).maxInboundMessageSize(RemoteStrategyWorker.MAX_MESSAGE_BYTES)
                .addService(new RemoteStrategyWorker(launcher)).build().start();
        channel = InProcessChannelBuilder.forName(name).disableRetry().build();
    }

    private GrpcStrategyTransport client(Duration requestTimeout) {
        return new GrpcStrategyTransport(channel, Duration.ofSeconds(30), requestTimeout);
    }

    private void assertInvalid(int version, long sequence) throws Exception {
        var outcome = new CompletableFuture<Status.Code>();
        var requests = StrategyWorkerGrpc.newStub(channel).run(new StreamObserver<StrategyResponse>() {
            @Override
            public void onNext(StrategyResponse response) {
                outcome.completeExceptionally(new AssertionError("unexpected response"));
            }

            @Override
            public void onError(Throwable failure) {
                outcome.complete(Status.fromThrowable(failure).getCode());
            }

            @Override
            public void onCompleted() {
                outcome.complete(Status.Code.OK);
            }
        });
        requests.onNext(StrategyRequest.newBuilder().setProtocolVersion(version).setSequence(sequence)
                .setStrategyJson("{}").build());
        assertEquals(Status.Code.INVALID_ARGUMENT, outcome.get(5, TimeUnit.SECONDS));
    }

    private abstract static class FakeTransport implements StrategyTransport {
        private final CountDownLatch closed;

        FakeTransport(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void cancel() {
            close();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
