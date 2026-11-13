package io.github.quant.worker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Launches one strategy in a provisioned Linux Docker image on a dedicated worker host. */
public final class DockerStrategySandbox {
    static final String JOB_LABEL = "io.github.quant.strategy-sandbox";
    private static final Pattern IMAGE_ID = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern ENTRYPOINT = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*:[A-Za-z_][A-Za-z0-9_]*");
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(10);
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(
            1, task -> Thread.ofPlatform().daemon().name("strategy-sandbox-deadline").unstarted(task));

    static {
        DEADLINES.setRemoveOnCancelPolicy(true);
    }

    private final Path docker;
    private final String image;
    private final Duration requestTimeout;
    private final Duration jobTimeout;

    public DockerStrategySandbox(Path docker, String image, Duration requestTimeout, Duration jobTimeout) {
        this.docker = Objects.requireNonNull(docker);
        if (!docker.isAbsolute() || !IMAGE_ID.matcher(image).matches()) {
            throw new IllegalArgumentException("an absolute Docker path and immutable image ID are required");
        }
        if (requestTimeout.compareTo(Duration.ofMillis(1)) < 0 || requestTimeout.compareTo(jobTimeout) > 0
                || jobTimeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("invalid request or job timeout");
        }
        this.image = image;
        this.requestTimeout = requestTimeout;
        this.jobTimeout = jobTimeout;
    }

    public StrategyTransport start(String entrypoint) throws IOException, InterruptedException {
        if (!ENTRYPOINT.matcher(entrypoint).matches()) {
            throw new IllegalArgumentException("strategy must be an importable module:callable");
        }
        String name = "quant-strategy-" + UUID.randomUUID();
        try {
            // Reject image-declared VOLUME instructions, which Docker otherwise mounts automatically.
            if (!control("image", "inspect", "--format", "{{len .Config.Volumes}}", image).equals("0")) {
                throw new IOException("strategy image declares volumes");
            }
        } catch (TimeoutException timeout) {
            throw new IOException("Docker image inspection timed out", timeout);
        }

        try {
            try {
                control(createArguments(name, entrypoint).toArray(String[]::new));
            } catch (TimeoutException timeout) {
                throw new IOException("Docker container creation timed out", timeout);
            }
            var process = StrategyProcess.start(command("start", "--attach", "--interactive", name), requestTimeout);
            return new Session(process, name);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            try {
                remove(name);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof TimeoutException) throw new IOException("Docker container creation timed out", failure);
            throw failure;
        }
    }

    List<String> createArguments(String name, String entrypoint) {
        String seconds = java.math.BigDecimal.valueOf(jobTimeout.toMillis(), 3).toPlainString() + "s";
        return List.of("create", "--interactive", "--pull=never", "--name", name,
                "--label", JOB_LABEL + "=true", "--network=none", "--read-only",
                "--user=65532:65532", "--cap-drop=ALL", "--security-opt=no-new-privileges=true",
                "--ipc=private", "--shm-size=16m", "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777",
                "--no-healthcheck", "--pids-limit=32", "--memory=256m", "--memory-swap=256m", "--cpus=1",
                "--ulimit", "nofile=128:128", "--log-driver=none", "--workdir=/opt/strategy",
                "--env=PYTHONNOUSERSITE=1", "--env=PYTHONDONTWRITEBYTECODE=1",
                "--entrypoint=/usr/bin/timeout", image, "--signal=KILL", seconds,
                "/usr/local/bin/python", "-I", "-B", "/opt/strategy/launch.py", entrypoint);
    }

    private List<String> command(String... arguments) {
        // Linux procfs cannot hold a Docker config file or user-configured proxy defaults.
        var command = new java.util.ArrayList<>(List.of(docker.toString(), "--config=/proc/self",
                "--host=unix:///var/run/docker.sock"));
        command.addAll(List.of(arguments));
        return command;
    }

    private String control(String... arguments) throws IOException, InterruptedException, TimeoutException {
        var builder = new ProcessBuilder(command(arguments)).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        var process = builder.start();
        try {
            if (!process.waitFor(CONTROL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Docker control request timed out");
            }
            if (process.exitValue() != 0) throw new IOException("Docker control request failed: " + arguments[0]);
            try (InputStream output = process.getInputStream()) {
                return new String(output.readNBytes(128), StandardCharsets.UTF_8).strip();
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void remove(String name) {
        // A caller may cancel while interrupted. Cleanup still gets a bounded attempt.
        boolean interrupted = Thread.interrupted();
        try {
            control("rm", "--force", "--volumes", name);
        } catch (IOException | InterruptedException | TimeoutException cleanup) {
            if (cleanup instanceof InterruptedException) interrupted = true;
            throw new IllegalStateException("failed to remove strategy container " + name, cleanup);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private final class Session implements StrategyTransport {
        private final StrategyProcess process;
        private final String name;
        private final ScheduledFuture<?> deadline;
        private boolean removed;

        private Session(StrategyProcess process, String name) {
            this.process = process;
            this.name = name;
            synchronized (this) {
                deadline = DEADLINES.schedule(() -> Thread.ofVirtual().start(this::close),
                        jobTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public String exchange(String request) throws IOException, TimeoutException, InterruptedException {
            try {
                return process.exchange(request);
            } catch (IOException | TimeoutException | InterruptedException failure) {
                try {
                    close();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override
        public void cancel() {
            close();
        }

        @Override
        public synchronized void close() {
            if (removed) return;
            deadline.cancel(false);
            process.close();
            remove(name);
            removed = true;
        }
    }
}
