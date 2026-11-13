package io.github.quant.market.clickhouse;

import io.github.quant.domain.Instrument;
import io.github.quant.market.Bar;
import io.github.quant.market.DataFeed;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

final class MarketBatchCodec {
    static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final Base64.Encoder BASE64 = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private MarketBatchCodec() {}

    static String encode(Collection<DataFeed.Observation> observations) {
        String rows = String.join("\n", observations.stream().map(MarketBatchCodec::row).distinct().sorted().toList());
        String payload = base64(rows);
        if (payload.length() > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("market batch exceeds 1 MiB");
        return payload;
    }

    private static String row(DataFeed.Observation observation) {
        String prefix = String.join("\t", base64(observation.instrument().symbol()),
                base64(observation.instrument().exchange()), observation.date().toString(),
                observation.eventTime().toString(), observation.availableTime().toString());
        return switch (observation) {
            case DataFeed.Open open -> prefix + "\tO\t" + open.price() + "\t" + open.availableQuantity();
            case DataFeed.Close close -> {
                Bar bar = close.bar();
                yield prefix + "\tC\t" + bar.open() + "\t" + bar.high() + "\t" + bar.low() + "\t"
                        + bar.close() + "\t" + bar.volume();
            }
        };
    }

    static List<DataFeed.Observation> decode(String payload) {
        if (payload.isEmpty() || payload.length() > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("invalid market batch size");
        }
        var observations = fromBase64(payload).lines().map(MarketBatchCodec::observation).toList();
        if (!payload.equals(encode(observations))) throw new IllegalArgumentException("noncanonical market batch");
        return observations;
    }

    private static DataFeed.Observation observation(String row) {
        String[] fields = row.split("\t", -1);
        if (fields.length < 6) throw new IllegalArgumentException("incomplete observation");
        var instrument = new Instrument(fromBase64(fields[0]), fromBase64(fields[1]));
        var date = LocalDate.parse(fields[2]);
        var eventTime = Instant.parse(fields[3]);
        var availableTime = Instant.parse(fields[4]);
        if (fields[5].equals("O") && fields.length == 8) {
            return new DataFeed.Open(instrument, date, eventTime, availableTime,
                    new BigDecimal(fields[6]), Long.parseLong(fields[7]));
        }
        if (fields[5].equals("C") && fields.length == 11) {
            var bar = new Bar(date, new BigDecimal(fields[6]), new BigDecimal(fields[7]),
                    new BigDecimal(fields[8]), new BigDecimal(fields[9]), Long.parseLong(fields[10]));
            return new DataFeed.Close(instrument, eventTime, availableTime, bar);
        }
        throw new IllegalArgumentException("invalid observation kind or field count");
    }

    static String base64(String value) {
        Objects.requireNonNull(value);
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("invalid Unicode in market batch");
        }
        return BASE64.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String fromBase64(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }

    static String operationId(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
