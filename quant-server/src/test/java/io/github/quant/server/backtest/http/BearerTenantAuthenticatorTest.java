package io.github.quant.server.backtest.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BearerTenantAuthenticatorTest {
    private static final String TOKEN_A = "unit-test-token-A";
    private static final String TOKEN_B = "unit-test-token-B";

    @TempDir Path directory;

    @Test
    void authenticatesOnlySingleValidBearerHeaderAndIgnoresUnverifiedTenant() throws Exception {
        var authenticator = new BearerTenantAuthenticator(
                Map.of(digest(TOKEN_A), "tenant-a", digest(TOKEN_B), "tenant-b"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var client = HttpClient.newHttpClient()) {
            server.createContext("/", exchange -> {
                try (exchange) {
                    var tenant = authenticator.authenticate(exchange);
                    byte[] body = tenant.orElse("").getBytes(StandardCharsets.US_ASCII);
                    exchange.sendResponseHeaders(tenant.isPresent() ? 200 : 401, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var tenantA = request(client, endpoint, "Bearer " + TOKEN_A);
            assertEquals(200, tenantA.statusCode());
            assertEquals("tenant-a", tenantA.body());
            var tenantB = request(client, endpoint, "bEaReR " + TOKEN_B);
            assertEquals(200, tenantB.statusCode());
            assertEquals("tenant-b", tenantB.body());

            assertEquals(401, request(client, endpoint).statusCode());
            assertEquals(401, request(client, endpoint, "Basic " + TOKEN_A).statusCode());
            assertEquals(401, request(client, endpoint, "Bearer  " + TOKEN_A).statusCode());
            assertEquals(401, request(client, endpoint, "Bearer unknown").statusCode());
            assertEquals(401, request(client, endpoint, "Bearer " + TOKEN_A + ", Bearer " + TOKEN_B).statusCode());
            assertEquals(401, request(client, endpoint, "Bearer " + TOKEN_A, "Bearer " + TOKEN_A).statusCode());
            assertEquals(401, request(client, endpoint, "Bearer " + TOKEN_A, "Bearer " + TOKEN_B).statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMalformedAndConflictingFingerprintsWithoutStoringPlaintext() throws Exception {
        String digest = digest(TOKEN_A);
        String uppercase = digest.toUpperCase(Locale.ROOT);
        assertThrows(IllegalArgumentException.class, () -> new BearerTenantAuthenticator(Map.of(TOKEN_A, "tenant-a")));
        assertThrows(IllegalArgumentException.class, () -> new BearerTenantAuthenticator(Map.of(digest, "tenant a")));
        assertThrows(IllegalArgumentException.class, () -> new BearerTenantAuthenticator(
                Map.of(digest, "tenant-a", uppercase, "tenant-b")));

        var file = directory.resolve("test-fingerprints.txt");
        Files.writeString(file, "# test credentials\n\n" + uppercase + " tenant-a\n");
        assertDoesNotThrow(() -> BearerTenantAuthenticator.fromFile(file));
        Files.writeString(file, digest + " tenant-a\n" + uppercase + " tenant-b\n");
        assertThrows(IOException.class, () -> BearerTenantAuthenticator.fromFile(file));
        Files.writeString(file, TOKEN_A + " tenant-a\n");
        var malformed = assertThrows(IOException.class, () -> BearerTenantAuthenticator.fromFile(file));
        assertFalse(malformed.toString().contains(TOKEN_A));
    }

    private static HttpResponse<String> request(HttpClient client, URI endpoint, String... authorizations)
            throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("X-Tenant-Id", "unverified-tenant");
        for (String authorization : authorizations) request.header("Authorization", authorization);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String digest(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
    }
}
