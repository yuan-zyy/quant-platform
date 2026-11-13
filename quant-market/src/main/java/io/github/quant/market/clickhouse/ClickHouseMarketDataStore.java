package io.github.quant.market.clickhouse;

import io.github.quant.market.DataFeed;
import io.github.quant.market.MarketDataSnapshot;
import io.github.quant.market.VersionedMarketDataStore;
import io.github.quant.market.ingest.MarketDataCsvImporter;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * A persistent batch journal for a bounded market dataset on one ClickHouse server.
 * Cooperating writers must use the same stable lock file on a filesystem with process file locking.
 */
public final class ClickHouseMarketDataStore {
    private static final String TABLE = "market_import_batches_v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private record Journal(VersionedMarketDataStore store, Map<Long, String> operations, long generation) {}
    private record Pending(long generation, String operationId) {}

    private final URI endpoint;
    private final URI bufferedEndpoint;
    private final String datasetVersion;
    private final String datasetBase64;
    private final Path writerLock;
    private final Path pendingFile;
    private final Map<String, String> headers;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public ClickHouseMarketDataStore(URI endpoint, String datasetVersion, Path writerLock) {
        this(endpoint, datasetVersion, writerLock, Map.of());
    }

    public ClickHouseMarketDataStore(URI endpoint, String datasetVersion, Path writerLock,
                                     Map<String, String> headers) {
        this.endpoint = Objects.requireNonNull(endpoint);
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("ClickHouse endpoint must be an HTTP(S) URI without user info or fragment");
        }
        if (endpoint.getQuery() != null && endpoint.getQuery().matches("(?s).*(?:^|&)wait_end_of_query(?:=|&|$).*")) {
            throw new IllegalArgumentException("ClickHouse endpoint must not override wait_end_of_query");
        }
        bufferedEndpoint = URI.create(endpoint.toASCIIString()
                + (endpoint.getRawQuery() == null ? "?" : "&") + "wait_end_of_query=1");
        if (datasetVersion.isBlank()) throw new IllegalArgumentException("dataset version must be nonblank");
        this.datasetVersion = datasetVersion;
        datasetBase64 = MarketBatchCodec.base64(datasetVersion);
        this.writerLock = writerLock.toAbsolutePath().normalize();
        pendingFile = this.writerLock.resolveSibling(this.writerLock.getFileName() + ".pending");
        this.headers = Map.copyOf(headers);
    }

    /** Returns input row count, including publications already present. The reader stays open. */
    public int importCsv(Reader source) throws IOException, InterruptedException {
        var observations = new MarketDataCsvImporter().read(source, datasetVersion);
        appendAll(observations);
        return observations.size();
    }

    /** Returns the committed journal generation. An identical retry does not advance it. */
    public long appendAll(Collection<DataFeed.Observation> observations) throws IOException, InterruptedException {
        String payload = MarketBatchCodec.encode(observations);
        String operation = MarketBatchCodec.operationId(payload);
        try (var channel = FileChannel.open(writerLock, CREATE, WRITE)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("market writer lock is already held");
                return appendLocked(payload, operation);
            } catch (OverlappingFileLockException locked) {
                throw new IOException("market writer lock is already held", locked);
            }
        }
    }

    private long appendLocked(String payload, String operation) throws IOException, InterruptedException {
        Journal journal = load();
        Pending pending = readPending();
        if (pending != null && pending.generation() <= journal.generation()) {
            if (!pending.operationId().equals(journal.operations().get(pending.generation()))) {
                throw new IOException("pending market import conflicts with committed generation");
            }
            Files.delete(pendingFile);
            pending = null;
        }

        if (pending != null && (pending.generation() != journal.generation() + 1
                || !pending.operationId().equals(operation))) {
            throw new IOException("a different market import is pending; retry the original batch");
        }
        if (payload.isEmpty()) return journal.generation();
        journal.store().appendAll(MarketBatchCodec.decode(payload));
        long next = journal.store().generation();
        if (next == journal.generation()) return next;
        if (pending == null) writePending(new Pending(next, operation));

        // The entire batch occupies one row. Read-back validation also catches competing generations.
        execute("INSERT INTO " + TABLE + " SETTINGS async_insert=0 FORMAT TabSeparated\n"
                + escape(datasetVersion) + "\t" + next + "\t" + operation + "\t" + payload + "\n");
        Journal committed = load();
        if (!operation.equals(committed.operations().get(next))) {
            throw new IOException("ClickHouse did not confirm market generation");
        }
        Files.delete(pendingFile);
        return next;
    }

    public MarketDataSnapshot snapshot(Instant asOf) throws IOException, InterruptedException {
        return load().store().snapshot(asOf);
    }

    public MarketDataSnapshot snapshot(Instant asOf, long generation) throws IOException, InterruptedException {
        return load().store().snapshot(asOf, generation);
    }

    private Journal load() throws IOException, InterruptedException {
        String rows = execute("SELECT DISTINCT import_generation, operation_id, payload FROM " + TABLE
                + " WHERE dataset_version = base64Decode('" + datasetBase64 + "')"
                + " ORDER BY import_generation, operation_id FORMAT TabSeparated");
        var store = new VersionedMarketDataStore(datasetVersion);
        Map<Long, String> operations = new HashMap<>();
        long last = 0;
        for (String row : rows.lines().toList()) {
            String[] fields = row.split("\t", -1);
            try {
                if (fields.length != 3) throw new IllegalArgumentException("invalid journal row");
                long generation = Long.parseLong(fields[0]);
                if (generation != last + 1 || !fields[1].equals(MarketBatchCodec.operationId(fields[2]))) {
                    throw new IllegalArgumentException("invalid generation or operation digest");
                }
                store.appendAll(MarketBatchCodec.decode(fields[2]));
                if (store.generation() != generation) {
                    throw new IllegalArgumentException("generation does not change dataset");
                }
                operations.put(generation, fields[1]);
                last = generation;
            } catch (RuntimeException invalid) {
                throw new IOException("invalid persisted market import journal after generation " + last, invalid);
            }
        }
        return new Journal(store, operations, last);
    }

    private String execute(String sql) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(bufferedEndpoint).timeout(TIMEOUT)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("ClickHouse returned HTTP " + response.statusCode());
        return response.body();
    }

    private Pending readPending() throws IOException {
        String marker;
        try {
            marker = Files.readString(pendingFile, StandardCharsets.US_ASCII);
        } catch (NoSuchFileException absent) {
            return null;
        }
        String[] fields = marker.strip().split(" ");
        if (fields.length != 3 || !fields[0].equals(scope()) || !fields[2].matches("[a-f0-9]{64}")) {
            throw new IOException("invalid or misconfigured pending market import marker");
        }
        try {
            long generation = Long.parseLong(fields[1]);
            if (generation <= 0) throw new NumberFormatException();
            return new Pending(generation, fields[2]);
        } catch (NumberFormatException invalid) {
            throw new IOException("invalid pending market generation", invalid);
        }
    }

    private void writePending(Pending pending) throws IOException {
        Path temp = Files.createTempFile(pendingFile.getParent(), "market-import-", ".pending");
        try {
            Files.writeString(temp, scope() + " " + pending.generation() + " " + pending.operationId() + "\n",
                    StandardCharsets.US_ASCII);
            try (var channel = FileChannel.open(temp, WRITE)) {
                channel.force(true);
            }
            Files.move(temp, pendingFile, ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String scope() {
        return MarketBatchCodec.operationId(MarketBatchCodec.base64(endpoint.toASCIIString() + "\n" + datasetVersion));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\0", "\\0");
    }
}
