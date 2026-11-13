package io.github.quant.server.backtest;

import com.sun.net.httpserver.HttpServer;
import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.Bar;
import io.github.quant.market.HistoricalDataFeed;
import io.github.quant.market.HistoricalDataFeed.Session;
import io.github.quant.oms.LimitOrder;
import io.github.quant.server.backtest.http.BacktestHttpApi;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BacktestScenarioReplayTest {
    @Test
    void submitsTrustedScenarioAndReturnsItsCompletedReplayOnlyToTheOwner() throws Exception {
        var stock = new Instrument("600000", "SSE");
        var firstDay = LocalDate.of(2026, 1, 5);
        var price = BigDecimal.TEN;
        var bars = List.of(firstDay, firstDay.plusDays(1)).stream()
                .map(day -> new Bar(day, price, price, price, price, 1000)).toList();
        var feed = new HistoricalDataFeed(bars.stream()
                .map(bar -> new Session(stock, bar, 100, HistoricalDataFeed.closeTime(bar))).toList());
        var scenario = new BacktestScenarioCatalog.Scenario(feed, BigDecimal.valueOf(10_000),
                Map.of(stock, TradingRules.forType(SecurityType.A_SHARE)), ExecutionCosts.NONE,
                () -> (close, context) -> close.date().equals(firstDay)
                        ? List.of(new LimitOrder.Request(stock, LimitOrder.Side.BUY, 100, price))
                        : List.of());
        var catalog = new BacktestScenarioCatalog(Map.of(new BacktestScenarioCatalog.Key("owner", "daily"), scenario));

        try (var scheduler = new BacktestTaskScheduler(1, 4, 10)) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var api = new BacktestHttpApi(scheduler, exchange -> switch (
                    java.util.Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "")) {
                case "Bearer owner-test-token" -> Optional.of("owner");
                case "Bearer other-test-token" -> Optional.of("other");
                default -> Optional.empty();
            }, catalog::resolve);
            server.createContext(BacktestHttpApi.PATH, api);
            server.start();
            try (var client = HttpClient.newHttpClient()) {
                URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                URI submit = base.resolve(BacktestHttpApi.PATH);
                var accepted = client.send(request(submit, "owner").header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("daily")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(202, accepted.statusCode(), accepted.body());
                URI status = base.resolve(accepted.headers().firstValue("Location").orElseThrow());
                var other = client.send(request(status, "other").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, other.statusCode());
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    while (true) {
                        var response = client.send(request(status, "owner").GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, response.statusCode(), response.body());
                        if (response.body().contains("SUCCEEDED")) {
                            assertTrue(response.body().matches("(?s).*\"fillCount\"\\s*:\\s*1.*"), response.body());
                            return;
                        }
                        assertFalse(response.body().contains("FAILED"), response.body());
                        Thread.sleep(10);
                    }
                });
            } finally {
                server.stop(0);
            }
        }
    }

    private static HttpRequest.Builder request(URI uri, String user) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + user + "-test-token");
    }
}
