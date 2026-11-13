package io.github.quant.server.backtest.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves bearer credentials to a tenant using configured SHA-256 fingerprints. */
public final class BearerTenantAuthenticator implements BacktestHttpApi.TenantAuthenticator {
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/\\-]++=*+");

    private record Credential(byte[] fingerprint, String tenant) {}

    private final List<Credential> credentials;

    public BearerTenantAuthenticator(Map<String, String> tenantBySha256) {
        var normalized = new HashMap<String, String>();
        var entries = new ArrayList<Credential>();
        for (var entry : tenantBySha256.entrySet()) {
            String fingerprint = entry.getKey();
            String tenant = entry.getValue();
            if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()
                    || tenant == null || !TENANT.matcher(tenant).matches()) {
                throw new IllegalArgumentException("invalid tenant credential configuration");
            }
            if (normalized.putIfAbsent(fingerprint.toLowerCase(Locale.ROOT), tenant) != null) {
                throw new IllegalArgumentException("duplicate tenant credential fingerprint");
            }
            entries.add(new Credential(HexFormat.of().parseHex(fingerprint), tenant));
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("at least one tenant credential is required");
        credentials = List.copyOf(entries);
    }

    /** Reads UTF-8 fingerprint/tenant pairs, one per line. Blank lines and full-line comments are ignored. */
    public static BearerTenantAuthenticator fromFile(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
        }
        if (bytes.length > MAX_CONFIG_BYTES) throw new IOException("tenant credential file is too large");
        var text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        var entries = new HashMap<String, String>();
        int lineNumber = 0;
        for (String line : text.lines().toList()) {
            lineNumber++;
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("[ \\t]+", -1);
            if (fields.length != 2 || !FINGERPRINT.matcher(fields[0]).matches()
                    || !TENANT.matcher(fields[1]).matches()
                    || entries.putIfAbsent(fields[0].toLowerCase(Locale.ROOT), fields[1]) != null) {
                throw new IOException("invalid tenant credential at line " + lineNumber);
            }
        }
        if (entries.isEmpty()) throw new IOException("tenant credential file is empty");
        return new BearerTenantAuthenticator(entries);
    }

    @Override
    public Optional<String> authenticate(HttpExchange exchange) {
        var values = exchange.getRequestHeaders().get("Authorization");
        if (values == null || values.size() != 1) return Optional.empty();
        String header = values.getFirst();
        if (header.length() <= 7 || header.length() > MAX_TOKEN_LENGTH + 7
                || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = header.substring(7);
        if (!TOKEN.matcher(token).matches()) return Optional.empty();
        byte[] supplied = digest(token);
        String tenant = null;
        // Compare every configured fingerprint, including after a match.
        for (var credential : credentials) {
            if (MessageDigest.isEqual(credential.fingerprint(), supplied)) tenant = credential.tenant();
        }
        return Optional.ofNullable(tenant);
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
