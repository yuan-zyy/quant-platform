package io.github.quant.server.backtest.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.quant.server.backtest.BacktestTaskScheduler;
import io.github.quant.server.backtest.BacktestTaskScheduler.BacktestJob;
import io.github.quant.server.backtest.BacktestTaskService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/** HTTP transport for authenticated tenants and registered server-side backtest jobs. */
public final class BacktestHttpApi implements HttpHandler {
    public static final String PATH = "/v1/backtests";
    private static final Pattern SCENARIO_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
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

    private final BacktestTaskService service;
    private final TenantAuthenticator authenticator;

    public BacktestHttpApi(BacktestTaskScheduler scheduler, TenantAuthenticator authenticator, JobResolver resolver) {
        this(inMemoryService(scheduler, resolver), authenticator);
    }

    public BacktestHttpApi(BacktestTaskService service, TenantAuthenticator authenticator) {
        this.service = Objects.requireNonNull(service);
        this.authenticator = Objects.requireNonNull(authenticator);
    }

    private static BacktestTaskService inMemoryService(BacktestTaskScheduler scheduler, JobResolver resolver) {
        Objects.requireNonNull(scheduler);
        Objects.requireNonNull(resolver);
        return new BacktestTaskService() {
            @Override
            public Optional<Task> submit(String tenant, String scenario, String ignoredIdempotencyKey) {
                return resolver.resolve(tenant, scenario).map(job -> Task.from(scheduler.submit(tenant, job)));
            }

            @Override
            public Optional<Task> get(String tenant, UUID id) {
                return scheduler.get(tenant, id).map(Task::from);
            }

            @Override
            public Optional<Task> cancel(String tenant, UUID id) {
                return scheduler.cancel(tenant, id).map(Task::from);
            }
        };
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var tenant = authenticator.authenticate(exchange).filter(value -> !value.isBlank());
            if (tenant.isEmpty()) {
                error(exchange, 401, "unauthorized");
                return;
            }
            try {
                route(exchange, tenant.get());
            } catch (BacktestTaskService.IdempotencyConflictException conflict) {
                error(exchange, 409, "idempotency_conflict");
            } catch (SQLException | RejectedExecutionException unavailable) {
                error(exchange, 503, "scheduler_unavailable");
            }
        }
    }

    private void route(HttpExchange exchange, String tenant) throws IOException, SQLException {
        String path = exchange.getRequestURI().getRawPath();
        if (PATH.equals(path)) {
            if ("POST".equals(exchange.getRequestMethod())) {
                submit(exchange, tenant);
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
            case "GET" -> task(exchange, service.get(tenant, id));
            case "DELETE" -> task(exchange, service.cancel(tenant, id));
            default -> methodNotAllowed(exchange, "GET, DELETE");
        }
    }

    private void submit(HttpExchange exchange, String tenant) throws IOException, SQLException {
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
        var keys = exchange.getRequestHeaders().get("Idempotency-Key");
        if (keys != null && (keys.size() != 1 || !IDEMPOTENCY_KEY.matcher(keys.getFirst()).matches())) {
            error(exchange, 400, "invalid_idempotency_key");
            return;
        }
        var submitted = service.submit(tenant, scenario, keys == null ? null : keys.getFirst());
        if (submitted.isEmpty()) {
            error(exchange, 404, "not_found");
            return;
        }
        exchange.getResponseHeaders().set("Location", PATH + "/" + submitted.get().id());
        send(exchange, 202, json(submitted.get()));
    }

    private static void task(HttpExchange exchange, Optional<BacktestTaskService.Task> result)
            throws IOException {
        if (result.isEmpty()) {
            error(exchange, 404, "not_found");
        } else {
            send(exchange, 200, json(result.get()));
        }
    }

    private static String json(BacktestTaskService.Task task) {
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
            var metrics = task.result();
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
