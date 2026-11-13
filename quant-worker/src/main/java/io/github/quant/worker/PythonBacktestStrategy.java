package io.github.quant.worker;

import io.github.quant.backtest.BacktestEngine;
import io.github.quant.market.DataFeed;
import io.github.quant.oms.LimitOrder;
import io.github.quant.strategy.api.StrategyProtocol;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Adapts one dedicated Python worker process to a daily backtest strategy. */
public final class PythonBacktestStrategy implements BacktestEngine.Strategy, AutoCloseable {
    private final StrategyTransport process;
    private long requestNumber;

    public PythonBacktestStrategy(StrategyTransport process) {
        this.process = Objects.requireNonNull(process);
    }

    @Override
    public List<LimitOrder.Request> onClose(DataFeed.Close close, BacktestEngine.Context context) {
        var bar = close.bar();
        var event = new StrategyProtocol.Event(close.instrument(), close.date(), close.eventTime(),
                close.availableTime(), context.processingTime(), bar.open(), bar.high(), bar.low(),
                bar.close(), bar.volume());
        var positions = context.portfolio().positions().entrySet().stream()
                .map(entry -> new StrategyProtocol.Position(entry.getKey(), entry.getValue()))
                .toList();
        String id = Long.toString(++requestNumber);
        var request = new StrategyProtocol.Request(id, event,
                new StrategyProtocol.Portfolio(context.portfolio().cash(), positions));

        try {
            var response = StrategyProtocol.decodeResponse(process.exchange(StrategyProtocol.encode(request)), id);
            return switch (response) {
                case StrategyProtocol.Orders orders -> orders.orders().stream()
                        .map(order -> new LimitOrder.Request(order.instrument(),
                                LimitOrder.Side.valueOf(order.side().name()), order.quantity(), order.limit()))
                        .toList();
                case StrategyProtocol.Failure failure ->
                        throw new IllegalStateException("Python strategy failed with code " + failure.code());
            };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw abort("Python strategy interrupted", interrupted);
        } catch (TimeoutException timeout) {
            throw abort("Python strategy timed out", timeout);
        } catch (IOException | RuntimeException failure) {
            throw abort("Python strategy exchange failed", failure);
        }
    }

    /** Stops the process and interrupts an exchange in progress. */
    public void cancel() {
        process.cancel();
    }

    @Override
    public void close() {
        process.close();
    }

    private IllegalStateException abort(String message, Exception cause) {
        try {
            process.cancel();
        } catch (RuntimeException cleanup) {
            cause.addSuppressed(cleanup);
        }
        return new IllegalStateException(message, cause);
    }
}
