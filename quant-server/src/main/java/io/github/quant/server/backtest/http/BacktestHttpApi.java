package io.github.quant.server.backtest.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.quant.server.backtest.BacktestTaskScheduler;
import io.github.quant.server.backtest.BacktestTaskScheduler.BacktestJob;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/** HTTP transport for authenticated tenants and registered server-side backtest jobs. */
public final class BacktestHttpApi implements HttpHandler {
    public static final String PATH = "/v1/backtests";
    private static final Pattern SCENARIO_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern TASK_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @FunctionalInterface
    public interface TenantAuthenticator {
        Optional<String> authenticate(HttpExchange exchange);
    }

    @FunctionalInterface
    public interface JobResolver {
        Optional<BacktestJob> resolve(String tenantId, String scenarioId);
    }

    private final BacktestTaskScheduler scheduler;
    private final TenantAuthenticator authenticator;
    private final JobResolver resolver;

    public BacktestHttpApi(BacktestTaskScheduler scheduler, TenantAuthenticator authenticator, JobResolver resolver) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.authenticator = Objects.requireNonNull(authenticator);
        this.resolver = Objects.requireNonNull(resolver);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var tenant = authenticator.authenticate(exchange).filter(value -> !value.isBlank());
            if (tenant.isEmpty()) {
                error(exchange, 401, "unauthorized");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (PATH.equals(path)) {
                if ("POST".equals(exchange.getRequestMethod())) {
                    submit(exchange, tenant.get());
                } else {
                    methodNotAllowed(exchange, "POST");
                }
                return;
            }
            String prefix = PATH + "/";
            if (!path.startsWith(prefix) || !TASK_ID.matcher(path.substring(prefix.length())).matches()) {
                error(exchange, 404, "not_found");
                return;
            }
            var id = UUID.fromString(path.substring(prefix.length()));
            switch (exchange.getRequestMethod()) {
                case "GET" -> task(exchange, scheduler.get(tenant.get(), id));
                case "DELETE" -> task(exchange, scheduler.cancel(tenant.get(), id));
                default -> methodNotAllowed(exchange, "GET, DELETE");
            }
        }
    }

    private void submit(HttpExchange exchange, String tenant) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !"text/plain".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            error(exchange, 415, "unsupported_media_type");
            return;
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(129);
        if (bytes.length > 128) {
            error(exchange, 413, "scenario_id_too_large");
            return;
        }
        String scenario = new String(bytes, StandardCharsets.US_ASCII);
        if (!SCENARIO_ID.matcher(scenario).matches()) {
            error(exchange, 400, "invalid_scenario_id");
            return;
        }
        var job = resolver.resolve(tenant, scenario);
        if (job.isEmpty()) {
            error(exchange, 404, "not_found");
            return;
        }
        BacktestTaskScheduler.Snapshot submitted;
        try {
            submitted = scheduler.submit(tenant, job.get());
        } catch (RejectedExecutionException unavailable) {
            error(exchange, 503, "scheduler_unavailable");
            return;
        }
        exchange.getResponseHeaders().set("Location", PATH + "/" + submitted.id());
        send(exchange, 202, json(submitted));
    }

    private static void task(HttpExchange exchange, Optional<BacktestTaskScheduler.Snapshot> result)
            throws IOException {
        if (result.isEmpty()) {
            error(exchange, 404, "not_found");
        } else {
            send(exchange, 200, json(result.get()));
        }
    }

    private static String json(BacktestTaskScheduler.Snapshot task) {
        // Every interpolated string comes from a UUID, enum or Instant.
        StringBuilder response = new StringBuilder()
                .append("{\"id\":\"").append(task.id()).append("\",\"status\":\"").append(task.status().name())
                .append("\",\"submittedAt\":").append(timestamp(task.submittedAt()))
                .append(",\"startedAt\":").append(timestamp(task.startedAt()))
                .append(",\"finishedAt\":").append(timestamp(task.finishedAt()))
                .append(",\"result\":");
        if (task.result() == null) {
            response.append("null");
        } else {
            var metrics = task.result().metrics();
            response.append("{\"endingEquity\":\"").append(metrics.endingEquity())
                    .append("\",\"totalReturn\":\"").append(metrics.totalReturn())
                    .append("\",\"maxDrawdown\":\"").append(metrics.maxDrawdown())
                    .append("\",\"fillCount\":").append(metrics.fillCount()).append('}');
        }
        return response.append('}').toString();
    }

    private static String timestamp(java.time.Instant timestamp) {
        return timestamp == null ? "null" : "\"" + timestamp + "\"";
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        error(exchange, 405, "method_not_allowed");
    }

    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        send(exchange, status, "{\"error\":\"" + code + "\"}");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
