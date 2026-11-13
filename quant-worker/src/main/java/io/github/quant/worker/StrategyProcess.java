package io.github.quant.worker;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** An isolated protocol stream for one trusted local strategy process. */
public final class StrategyProcess implements StrategyTransport {
    public static final int MAX_LINE_BYTES = 1_048_576;

    private final Process process;
    private final InputStream responses;
    private final long requestTimeoutNanos;
    private final ExecutorService io;
    private final Object lifecycle = new Object();
    private Future<String> inFlight;
    private boolean closed;

    private StrategyProcess(Process process, long requestTimeoutNanos) {
        this.process = process;
        this.requestTimeoutNanos = requestTimeoutNanos;
        responses = new BufferedInputStream(process.getInputStream());
        io = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform().daemon().name("strategy-process-io").unstarted(task));
    }

    public static StrategyProcess start(List<String> command, Path workingDirectory, Duration requestTimeout)
            throws IOException {
        var builder = new ProcessBuilder(List.copyOf(command)).directory(Objects.requireNonNull(workingDirectory).toFile());
        return start(builder, requestTimeout);
    }

    static StrategyProcess start(List<String> command, Duration requestTimeout) throws IOException {
        var builder = new ProcessBuilder(List.copyOf(command));
        builder.environment().clear();
        return start(builder, requestTimeout);
    }

    private static StrategyProcess start(ProcessBuilder builder, Duration requestTimeout) throws IOException {
        long timeoutNanos = requestTimeout.toNanos();
        if (timeoutNanos <= 0) throw new IllegalArgumentException("request timeout must be positive");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return new StrategyProcess(builder.start(), timeoutNanos);
    }

    /**
     * Sends a single UTF-8 line and waits for one complete response. A transport failure closes the session.
     * The request timeout covers both the write and the read.
     */
    public String exchange(String request) throws IOException, TimeoutException, InterruptedException {
        var bytes = encodeRequest(request);
        Future<String> response;
        synchronized (lifecycle) {
            if (closed) throw new IOException("strategy process is closed");
            if (inFlight != null) throw new IllegalStateException("strategy request already in flight");
            response = io.submit(() -> sendAndReceive(bytes));
            inFlight = response;
        }
        try {
            return response.get(requestTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | InterruptedException e) {
            close();
            throw e;
        } catch (CancellationException e) {
            throw new IOException("strategy request canceled", e);
        } catch (ExecutionException e) {
            close();
            if (e.getCause() instanceof IOException transportFailure) throw transportFailure;
            throw new IOException("strategy transport failed", e.getCause());
        } finally {
            synchronized (lifecycle) {
                inFlight = null;
            }
        }
    }

    private static byte[] encodeRequest(String request) throws IOException {
        if (request.isEmpty() || request.length() > MAX_LINE_BYTES
                || request.indexOf('\n') >= 0 || request.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("request must be a single nonempty line within the byte limit");
        }
        var encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(request));
        if (encoded.remaining() > MAX_LINE_BYTES) throw new IllegalArgumentException("request exceeds byte limit");
        var bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private String sendAndReceive(byte[] request) throws IOException {
        var output = process.getOutputStream();
        output.write(request);
        output.write('\n');
        output.flush();

        var line = new ByteArrayOutputStream();
        int next;
        while ((next = responses.read()) != '\n') {
            if (next == -1) throw new EOFException("strategy process exited before a complete response");
            if (line.size() == MAX_LINE_BYTES) throw new IOException("strategy response exceeds byte limit");
            line.write(next);
        }
        var bytes = line.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') length--;
        if (length == 0) throw new IOException("strategy process returned an empty response");
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
    }

    public void cancel() {
        close();
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            if (inFlight != null) inFlight.cancel(true);
        }
        io.shutdownNow();

        // Kill through ProcessHandle so cancellation does not wait for an I/O thread blocked on a pipe.
        ProcessHandle handle = process.toHandle();
        var descendants = handle.descendants().toList();
        handle.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
