package io.github.quant.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class StrategyProcessTest {
    @Test
    void roundTripsUtf8OverMultipleExchangesAndKeepsStderrOffProtocol() throws Exception {
        try (var worker = start("echo", Duration.ofSeconds(10))) {
            assertEquals("沪市", worker.exchange("沪市"));
            assertEquals("{\"hello\":1}", worker.exchange("{\"hello\":1}"));
            assertThrows(IllegalArgumentException.class, () -> worker.exchange("first\nsecond"));
            assertThrows(IllegalArgumentException.class, () -> worker.exchange("沪".repeat(400_000)));
            assertEquals("valid", worker.exchange("valid"));
        }
    }

    @Test
    void timeoutIncludesABlockedRequestWrite() throws Exception {
        try (var worker = start("ignore-input", Duration.ofMillis(250))) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThrows(TimeoutException.class,
                            () -> worker.exchange("x".repeat(StrategyProcess.MAX_LINE_BYTES))));
            assertThrows(IOException.class, () -> worker.exchange("closed"));
        }
    }

    @Test
    void rejectsTruncatedOversizedAndMalformedResponses() throws Exception {
        assertFatal("truncated", EOFException.class);
        assertFatal("oversize", IOException.class);
        assertFatal("malformed", CharacterCodingException.class);
    }

    @Test
    void cancellationReleasesCallerAndTerminatesChild(@TempDir Path directory) throws Exception {
        try (var worker = start("hang", Duration.ofSeconds(20), directory)) {
            var pending = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try {
                    pending.complete(worker.exchange("start"));
                } catch (Exception failure) {
                    pending.completeExceptionally(failure);
                }
            });
            Path pidFile = directory.resolve("worker.pid");
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (!Files.exists(pidFile) || Files.size(pidFile) == 0) Thread.sleep(10);
            });
            long pid = Long.parseLong(Files.readString(pidFile));
            var handle = ProcessHandle.of(pid).orElseThrow();
            assertThrows(IllegalStateException.class, () -> worker.exchange("concurrent"));
            worker.cancel();
            var failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            handle.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(handle.isAlive());
        }
    }

    private void assertFatal(String mode, Class<? extends IOException> failureType) throws Exception {
        try (var worker = start(mode, Duration.ofSeconds(10))) {
            assertThrows(failureType, () -> worker.exchange("test"));
            assertThrows(IOException.class, () -> worker.exchange("closed"));
        }
    }

    private StrategyProcess start(String mode, Duration timeout) throws Exception {
        return start(mode, timeout, Path.of("").toAbsolutePath());
    }

    private StrategyProcess start(String mode, Duration timeout, Path directory) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executable);
        Path classes = Path.of(StrategyProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path productionClasses = Path.of(StrategyProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classpath = classes + java.io.File.pathSeparator + productionClasses;
        return StrategyProcess.start(List.of(javaExecutable.toString(), "-cp", classpath,
                StrategyProcessFixture.class.getName(), mode), directory, timeout);
    }
}
