package io.github.quant.worker;

import io.github.quant.backtest.BacktestEngine;
import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.Bar;
import io.github.quant.market.HistoricalDataFeed;
import io.github.quant.market.HistoricalDataFeed.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DockerStrategySandboxTest {
    private static final Path DOCKER = Path.of("/usr/bin/docker").toAbsolutePath();
    private static final String IMAGE_ID = "sha256:" + "a".repeat(64);
    private static final Duration REQUEST = Duration.ofSeconds(10);
    private static final Duration JOB = Duration.ofSeconds(20);

    @Test
    void requiresImmutableImageAndConstrainedEntrypoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new DockerStrategySandbox(DOCKER, "strategy:latest", REQUEST, JOB));
        assertThrows(IllegalArgumentException.class,
                () -> new DockerStrategySandbox(Path.of("docker"), IMAGE_ID, REQUEST, JOB));
        assertThrows(IllegalArgumentException.class,
                () -> new DockerStrategySandbox(DOCKER, IMAGE_ID, JOB, REQUEST));
        var sandbox = new DockerStrategySandbox(DOCKER, IMAGE_ID, REQUEST, JOB);
        assertThrows(IllegalArgumentException.class, () -> sandbox.start("--privileged"));
        assertThrows(IllegalArgumentException.class, () -> sandbox.start("pkg:run; id"));
    }

    @Test
    void constrainsDockerPrivilegesAndResourceUse() {
        var sandbox = new DockerStrategySandbox(DOCKER, IMAGE_ID, REQUEST, JOB);
        var command = sandbox.createArguments("quant-strategy-test", "fixture:run");
        assertTrue(command.containsAll(List.of("--pull=never", "--network=none", "--read-only",
                "--user=65532:65532", "--cap-drop=ALL", "--security-opt=no-new-privileges=true",
                "--no-healthcheck", "--pids-limit=32", "--memory=256m", "--memory-swap=256m", "--cpus=1", "--log-driver=none")));
        assertFalse(command.contains("--mount"));
        assertFalse(command.contains("--privileged"));
        assertEquals(List.of(IMAGE_ID, "--signal=KILL", "20.000s", "/usr/local/bin/python",
                        "-I", "-B", "/opt/strategy/launch.py", "fixture:run"),
                command.subList(command.indexOf(IMAGE_ID), command.size()));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @EnabledIfSystemProperty(named = "quant.sandbox.image", matches = "sha256:[a-f0-9]{64}")
    void executesReplayWithContainerBoundaryProbes() throws Exception {
        var before = sandboxContainers();
        var sandbox = new DockerStrategySandbox(DOCKER, System.getProperty("quant.sandbox.image"), REQUEST, JOB);
        var instrument = new Instrument("600000", "SSE");
        var price = BigDecimal.TEN;
        var first = new Bar(LocalDate.of(2026, 1, 5), price, price, price, price, 1000);
        var second = new Bar(first.date().plusDays(1), price, price, price, price, 1000);
        var feed = new HistoricalDataFeed(List.of(
                new Session(instrument, first, 100, HistoricalDataFeed.closeTime(first)),
                new Session(instrument, second, 100, HistoricalDataFeed.closeTime(second))));

        try (var strategy = new PythonBacktestStrategy(sandbox.start("smoke_strategy:probe"))) {
            var result = new BacktestEngine().run(feed, BigDecimal.valueOf(10_000),
                    Map.of(instrument, TradingRules.forType(SecurityType.A_SHARE)), ExecutionCosts.NONE, strategy);
            assertEquals(1, result.fills().size());
            assertEquals(second.date(), result.fills().getFirst().tradeDate());
        }
        assertEquals(before, sandboxContainers());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @EnabledIfSystemProperty(named = "quant.sandbox.image", matches = "sha256:[a-f0-9]{64}")
    void removesIdleContainerAtJobDeadline() throws Exception {
        var before = sandboxContainers();
        var sandbox = new DockerStrategySandbox(DOCKER, System.getProperty("quant.sandbox.image"),
                Duration.ofSeconds(2), Duration.ofSeconds(3));
        try (var strategy = sandbox.start("smoke_strategy:probe")) {
            assertEquals(before.size() + 1, sandboxContainers().size());
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                while (!before.equals(sandboxContainers())) Thread.sleep(100);
            });
            assertThrows(java.io.IOException.class, () -> strategy.exchange("{}"));
        }
    }

    private static Set<String> sandboxContainers() throws Exception {
        var process = new ProcessBuilder(DOCKER.toString(), "--host=unix:///var/run/docker.sock", "ps", "--all",
                "--quiet", "--filter", "label=" + DockerStrategySandbox.JOB_LABEL + "=true")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Docker list timed out");
            assertEquals(0, process.exitValue());
            try (var reader = process.inputReader()) {
                return reader.lines().collect(Collectors.toSet());
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
