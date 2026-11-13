package io.github.quant.worker;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/** One dedicated, cancellable strategy session. */
public interface StrategyTransport extends AutoCloseable {
    String exchange(String request) throws IOException, TimeoutException, InterruptedException;

    void cancel();

    @Override
    void close();
}
