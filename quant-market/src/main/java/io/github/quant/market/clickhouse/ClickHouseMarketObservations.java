package io.github.quant.market.clickhouse;

import io.github.quant.domain.Instrument;
import io.github.quant.market.Bar;
import io.github.quant.market.DataFeed;
import io.github.quant.market.VersionedMarketDataStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads a bounded instrument slice from the analytical view of the validated market journal. */
public final class ClickHouseMarketObservations {
    private static final int MAX_ROWS = 10_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String NULL = "\\N";
    private final URI endpoint;
    private final Map<String, String> headers;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public ClickHouseMarketObservations(URI endpoint, Map<String, String> headers) {
        Objects.requireNonNull(endpoint);
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("ClickHouse endpoint must be an HTTP(S) URI without user info or fragment");
        }
        if (endpoint.getQuery() != null && endpoint.getQuery().matches("(?s).*(?:^|&)wait_end_of_query(?:=|&|$).*")) {
            throw new IllegalArgumentException("ClickHouse endpoint must not override wait_end_of_query");
        }
        this.endpoint = URI.create(endpoint.toASCIIString()
                + (endpoint.getRawQuery() == null ? "?" : "&") + "wait_end_of_query=1");
        this.headers = Map.copyOf(headers);
    }

    public record Snapshot(String datasetVersion, Instrument instrument, LocalDate fromInclusive,
                           LocalDate toInclusive, Instant asOf, long generation,
                           List<DataFeed.Observation> observations, DataFeed replayFeed) {
        public Snapshot {
            observations = List.copyOf(observations);
            Objects.requireNonNull(replayFeed);
        }
    }

    /**
     * Reads a pinned generation from the analytical view. Obtain the generation from a validated journal
     * snapshot first. The row cap applies to the selected instrument before Java filters the date range.
     */
    public Snapshot snapshot(String datasetVersion, Instrument instrument, LocalDate fromInclusive,
                             LocalDate toInclusive, Instant asOf, long generation)
            throws IOException, InterruptedException {
        Objects.requireNonNull(asOf);
        if (datasetVersion.isBlank() || fromInclusive.isAfter(toInclusive) || generation < 0) {
            throw new IllegalArgumentException("invalid market dataset, date range or generation");
        }
        String sql = """
                SELECT session_date, observation_kind, event_time, available_time, open_price,
                       opening_quantity, high_price, low_price, close_price, session_volume
                FROM market_observations_view_v1
                WHERE dataset_version = %s AND exchange = %s AND symbol = %s
                  AND import_generation <= %d
                LIMIT %d
                SETTINGS max_result_bytes = 16777216, result_overflow_mode = 'throw'
                FORMAT TabSeparated
                """.formatted(literal(datasetVersion), literal(instrument.exchange()), literal(instrument.symbol()),
                generation, MAX_ROWS + 1);
        var rows = execute(sql).lines().toList();
        if (rows.size() > MAX_ROWS) throw new IOException("market observation query exceeds the row limit");
        var observations = new ArrayList<DataFeed.Observation>();
        var store = new VersionedMarketDataStore(datasetVersion);
        try {
            for (String row : rows) {
                var observation = parse(instrument, row);
                if (!observation.date().isBefore(fromInclusive) && !observation.date().isAfter(toInclusive)) {
                    observations.add(observation);
                }
            }
            store.appendAll(observations);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid ClickHouse market observation data", invalid);
        }
        var result = store.snapshot(asOf);
        return new Snapshot(datasetVersion, instrument, fromInclusive, toInclusive, asOf, generation,
                result.observations(), result.replayFeed());
    }

    private String execute(String sql) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("ClickHouse returned HTTP " + response.statusCode());
        return response.body();
    }

    private static String literal(String value) {
        return "base64Decode('" + MarketBatchCodec.base64(value) + "')";
    }

    private static DataFeed.Observation parse(Instrument instrument, String row) {
        var fields = row.split("\t", -1);
        if (fields.length != 10) throw new IllegalArgumentException("invalid field count");
        var date = LocalDate.parse(fields[0]);
        var eventTime = Instant.parse(fields[2]);
        var availableTime = Instant.parse(fields[3]);
        var open = new BigDecimal(fields[4]);
        return switch (fields[1]) {
            case "open" -> {
                if (!NULL.equals(fields[6]) || !NULL.equals(fields[7])
                        || !NULL.equals(fields[8]) || !NULL.equals(fields[9])) {
                    throw new IllegalArgumentException("unexpected closing fields on opening observation");
                }
                yield new DataFeed.Open(instrument, date, eventTime, availableTime, open, Long.parseLong(fields[5]));
            }
            case "close" -> {
                if (!NULL.equals(fields[5])) throw new IllegalArgumentException("unexpected opening quantity");
                var bar = new Bar(date, open, new BigDecimal(fields[6]), new BigDecimal(fields[7]),
                        new BigDecimal(fields[8]), Long.parseLong(fields[9]));
                yield new DataFeed.Close(instrument, eventTime, availableTime, bar);
            }
            default -> throw new IllegalArgumentException("invalid observation kind");
        };
    }
}
