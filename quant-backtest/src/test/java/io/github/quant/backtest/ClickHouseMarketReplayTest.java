package io.github.quant.backtest;

import com.sun.net.httpserver.HttpServer;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.SecurityType;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.DataFeed;
import io.github.quant.market.clickhouse.ClickHouseMarketDataStore;
import io.github.quant.oms.LimitOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "quant.clickhouse.url", matches = "https?://.+")
class ClickHouseMarketReplayTest {
    private static final Instrument STOCK = new Instrument("600000", "SSE");
    private static final Instant CUTOFF = Instant.parse("2026-01-07T00:00:00Z");
    private static final String HEADER = "dataset_version,event_type,symbol,exchange,session_date,event_time,"
            + "available_time,open,high,low,close,volume,opening_quantity\n";
    private static final String SOURCE = """
            %1$s,CLOSE,600000,SSE,2026-01-05,2026-01-05T07:00:00Z,2026-01-05T07:01:00Z,10,11,9,10,1000,
            %1$s,OPEN,600000,SSE,2026-01-06,2026-01-06T01:30:00Z,2026-01-06T01:30:00Z,10,,,,,100
            %1$s,CLOSE,600000,SSE,2026-01-06,2026-01-06T07:00:00Z,2026-01-06T07:01:00Z,10,11,9,11,1000,
            """;
    private static final String REVISION = """
            %s,CLOSE,600000,SSE,2026-01-05,2026-01-05T07:00:00Z,2026-01-06T07:05:00Z,10,19,9,19,1000,
            """;

    @TempDir Path directory;

    @Test
    void persistsCsvAcrossInstancesAndPreservesHistoricalTradeDecisions() throws Exception {
        var endpoint = endpoint();
        String version = UUID.randomUUID().toString();
        Path lock = directory.resolve("writer.lock");
        var writer = store(endpoint, version, lock);
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            assertThrows(IOException.class, () -> writer.importCsv(csv(SOURCE, version)));
            assertEquals(0, writer.snapshot(CUTOFF).generation());
        }
        assertEquals(3, writer.importCsv(csv(SOURCE, version)));
        assertEquals(3, writer.importCsv(csv(SOURCE, version)));

        var reopened = store(endpoint, version, lock);
        var original = reopened.snapshot(CUTOFF);
        assertEquals(1, original.generation());
        assertEquals(1, reopened.importCsv(csv(REVISION, version)));
        var revised = writer.snapshot(CUTOFF);
        assertEquals(2, revised.generation());
        assertEquals(original.observations(), writer.snapshot(CUTOFF, 1).observations());
        var monday = LocalDate.of(2026, 1, 5);
        var corrected = revised.observations().stream()
                .filter(event -> event instanceof DataFeed.Close && event.date().equals(monday))
                .map(DataFeed.Close.class::cast).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.valueOf(19).compareTo(corrected.bar().close()));

        var result = new BacktestEngine().run(revised.replayFeed(), BigDecimal.valueOf(10_000),
                Map.of(STOCK, TradingRules.forType(SecurityType.A_SHARE)), ExecutionCosts.NONE,
                (close, context) -> close.date().equals(monday) && close.bar().close().compareTo(BigDecimal.TEN) == 0
                        ? List.of(new LimitOrder.Request(STOCK, LimitOrder.Side.BUY, 100, BigDecimal.TEN))
                        : List.of());
        assertEquals(1, result.fills().size());
        assertEquals(monday.plusDays(1), result.fills().getFirst().tradeDate());
    }

    @Test
    void resolvesUncertainWritesWithoutAssigningAnotherBatchToTheSameGeneration() throws Exception {
        var endpoint = endpoint();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var insertCount = new AtomicInteger();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            try (exchange) {
                var body = exchange.getRequestBody().readAllBytes();
                String sql = new String(body, StandardCharsets.UTF_8);
                int attempt = sql.startsWith("INSERT INTO market_import_batches_v1")
                        ? insertCount.incrementAndGet() : 0;
                if (attempt == 1) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                var upstream = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                headers().forEach(upstream::header);
                var response = client.send(upstream.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (attempt == 2 && response.statusCode() == 200) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                exchange.sendResponseHeaders(response.statusCode(), response.body().length == 0 ? -1 : response.body().length);
                exchange.getResponseBody().write(response.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        proxy.start();
        try {
            URI throughProxy = URI.create("http://127.0.0.1:" + proxy.getAddress().getPort() + "/");
            String version = UUID.randomUUID().toString();
            Path lock = directory.resolve("recovery.lock");
            var writer = store(throughProxy, version, lock);
            assertThrows(IOException.class, () -> writer.importCsv(csv(SOURCE, version)));
            assertEquals(0, writer.snapshot(CUTOFF).generation());
            assertThrows(IOException.class, () -> writer.importCsv(csv(REVISION, version)));
            assertEquals(1, insertCount.get());
            assertThrows(IOException.class, () -> writer.importCsv(csv(SOURCE, version)));
            var reopened = store(throughProxy, version, lock);
            assertEquals(3, reopened.importCsv(csv(SOURCE, version)));
            assertEquals(1, reopened.snapshot(CUTOFF).generation());
            assertEquals(2, insertCount.get());
            assertEquals(1, reopened.importCsv(csv(REVISION, version)));
            assertEquals(2, reopened.snapshot(CUTOFF).generation());
        } finally {
            proxy.stop(0);
        }
    }

    private static ClickHouseMarketDataStore store(URI endpoint, String version, Path lock) {
        return new ClickHouseMarketDataStore(endpoint, version, lock, headers());
    }

    private static URI endpoint() {
        return URI.create(System.getProperty("quant.clickhouse.url"));
    }

    private static Map<String, String> headers() {
        String user = System.getenv("QUANT_CLICKHOUSE_USER");
        return user == null ? Map.of() : Map.of("X-ClickHouse-User", user,
                "X-ClickHouse-Key", System.getenv("QUANT_CLICKHOUSE_PASSWORD"));
    }

    private static StringReader csv(String data, String version) {
        return new StringReader(HEADER + data.formatted(version));
    }
}
