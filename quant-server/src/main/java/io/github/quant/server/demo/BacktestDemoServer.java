package io.github.quant.server.demo;

import com.sun.net.httpserver.HttpServer;
import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.Bar;
import io.github.quant.market.HistoricalDataFeed;
import io.github.quant.oms.LimitOrder;
import io.github.quant.server.backtest.BacktestScenarioCatalog;
import io.github.quant.server.backtest.BacktestTaskScheduler;
import io.github.quant.server.backtest.http.BacktestHttpApi;
import io.github.quant.server.backtest.http.BearerTenantAuthenticator;
import io.github.quant.server.backtest.http.InFlightRequestGuard;
import io.github.quant.server.health.HealthHttpHandler;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Loopback-only demonstration server with a fixed sample scenario. */
public final class BacktestDemoServer implements AutoCloseable {
    private static final String TENANT = "local-demo";
    private static final String SCENARIO = "demo-buy-hold";
    private static final String CANCEL_SCENARIO = "demo-cancel";

    private final HttpServer server;
    private final BacktestTaskScheduler scheduler;
    private final ExecutorService requests;
    private final ScheduledThreadPoolExecutor deadlines;
    private final HealthHttpHandler health;

    private BacktestDemoServer(HttpServer server, BacktestTaskScheduler scheduler, ExecutorService requests,
                               ScheduledThreadPoolExecutor deadlines, HealthHttpHandler health) {
        this.server = server;
        this.scheduler = scheduler;
        this.requests = requests;
        this.deadlines = deadlines;
        this.health = health;
    }

    public static void main(String[] args) throws Exception {
        String portText = System.getenv().getOrDefault("QUANT_DEMO_PORT", "8080");
        int port = Integer.parseInt(portText);
        String token = System.getenv("QUANT_DEMO_TOKEN");
        String credentials = System.getenv("QUANT_DEMO_CREDENTIALS");
        if (token != null && credentials != null) {
            throw new IllegalArgumentException("Set only one demo credential source");
        }
        var demo = credentials == null ? create(port, token)
                : create(port, BearerTenantAuthenticator.fromFile(Path.of(credentials)));
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("backtest-demo-shutdown").unstarted(demo::close));
        demo.start();
        System.out.println("Backtest demo listening at http://127.0.0.1:" + demo.port());
    }

    public static BacktestDemoServer create(int port, String token) throws IOException {
        if (token == null || !token.matches("[A-Za-z0-9._~+/-]{16,128}")) {
            throw new IllegalArgumentException("Set QUANT_DEMO_TOKEN to 16–128 bearer-token characters");
        }
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
        return create(port, exchange -> {
            var authorization = exchange.getRequestHeaders().get("Authorization");
            if (authorization == null || authorization.size() != 1) return Optional.empty();
            byte[] supplied = authorization.getFirst().getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, supplied) ? Optional.of(TENANT) : Optional.empty();
        });
    }

    private static BacktestDemoServer create(int port, BacktestHttpApi.TenantAuthenticator authenticator) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid QUANT_DEMO_PORT");
        var catalog = catalog();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        var scheduler = new BacktestTaskScheduler(2, 8, 32);
        var requests = Executors.newVirtualThreadPerTaskExecutor();
        var deadlines = new ScheduledThreadPoolExecutor(1);
        deadlines.setRemoveOnCancelPolicy(true);
        server.setExecutor(requests);
        var api = new BacktestHttpApi(scheduler, authenticator, catalog::resolve);
        server.createContext(BacktestHttpApi.PATH, new InFlightRequestGuard(8, api, deadlines, Duration.ofSeconds(5)));
        var health = new HealthHttpHandler(Duration.ofMillis(500), () -> !requests.isShutdown());
        server.createContext("/health", health);
        return new BacktestDemoServer(server, scheduler, requests, deadlines, health);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        requests.shutdownNow();
        deadlines.shutdownNow();
        health.close();
        scheduler.close();
    }

    private static BacktestScenarioCatalog catalog() {
        var stock = new Instrument("600000", "SSE");
        var firstDate = LocalDate.of(2026, 1, 5);
        var feed = new HistoricalDataFeed(List.of(
                session(stock, firstDate, "10.00", "10.20"),
                session(stock, firstDate.plusDays(1), "10.10", "10.60"),
                session(stock, firstDate.plusDays(2), "10.65", "10.85")));
        var scenario = new BacktestScenarioCatalog.Scenario(feed, new BigDecimal("10000"),
                Map.of(stock, TradingRules.forType(SecurityType.A_SHARE)), ExecutionCosts.NONE,
                () -> (close, context) -> close.date().equals(firstDate)
                        ? List.of(new LimitOrder.Request(stock, LimitOrder.Side.BUY, 100, close.bar().close()))
                        : List.of());
        var cancelScenario = new BacktestScenarioCatalog.Scenario(feed, scenario.startingCash(), scenario.rules(),
                ExecutionCosts.NONE, () -> (close, context) -> {
                    if (close.date().equals(firstDate)) {
                        try {
                            Thread.sleep(10_000);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Demo scenario interrupted", interrupted);
                        }
                    }
                    return List.of();
                });
        return new BacktestScenarioCatalog(Map.of(
                new BacktestScenarioCatalog.Key(TENANT, SCENARIO), scenario,
                new BacktestScenarioCatalog.Key(TENANT, CANCEL_SCENARIO), cancelScenario));
    }

    private static HistoricalDataFeed.Session session(Instrument instrument, LocalDate date,
                                                       String openPrice, String closePrice) {
        var open = new BigDecimal(openPrice);
        var close = new BigDecimal(closePrice);
        var bar = new Bar(date, open, open.max(close), open.min(close), close, 100_000);
        return new HistoricalDataFeed.Session(instrument, bar, 1_000, HistoricalDataFeed.closeTime(bar));
    }
}
