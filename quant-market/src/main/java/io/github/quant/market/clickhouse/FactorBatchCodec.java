package io.github.quant.market.clickhouse;

import io.github.quant.domain.Instrument;
import io.github.quant.market.factor.FactorStore;
import io.github.quant.market.factor.FactorStore.Definition;
import io.github.quant.market.factor.FactorStore.FactorVersion;
import io.github.quant.market.factor.FactorStore.Value;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;

final class FactorBatchCodec {
    private FactorBatchCodec() {}

    static String encode(Definition definition) {
        return payload(String.join("\t", "D", version(definition.factor()),
                MarketBatchCodec.base64(definition.description()), MarketBatchCodec.base64(definition.unit()),
                definition.availableTime().toString()));
    }

    static String encode(FactorVersion factor, Collection<Value> values) {
        String rows = String.join("\n", values.stream().map(FactorBatchCodec::row).distinct().sorted().toList());
        return payload("V\t" + version(factor) + "\n" + rows);
    }

    private static String version(FactorVersion factor) {
        return MarketBatchCodec.base64(factor.name()) + "\t" + MarketBatchCodec.base64(factor.version());
    }

    private static String row(Value value) {
        return String.join("\t", MarketBatchCodec.base64(value.instrument().symbol()),
                MarketBatchCodec.base64(value.instrument().exchange()), value.eventTime().toString(),
                value.availableTime().toString(), value.value().toString());
    }

    private static String payload(String data) {
        String encoded = MarketBatchCodec.base64(data);
        if (encoded.length() > MarketBatchCodec.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("factor batch exceeds 1 MiB");
        }
        return encoded;
    }

    static void apply(FactorStore store, String payload) {
        if (payload.isEmpty() || payload.length() > MarketBatchCodec.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("invalid factor batch size");
        }
        var lines = decode(payload).lines().toList();
        var header = lines.getFirst().split("\t", -1);
        if (header.length < 3) throw new IllegalArgumentException("incomplete factor batch");
        var version = new FactorVersion(decode(header[1]), decode(header[2]));
        if (header[0].equals("D") && header.length == 6 && lines.size() == 1) {
            var definition = new Definition(version, decode(header[3]), decode(header[4]), Instant.parse(header[5]));
            requireCanonical(payload, encode(definition));
            store.register(definition);
        } else if (header[0].equals("V") && header.length == 3) {
            var values = new ArrayList<Value>();
            for (int index = 1; index < lines.size(); index++) {
                var fields = lines.get(index).split("\t", -1);
                if (fields.length != 5) throw new IllegalArgumentException("invalid factor value fields");
                values.add(new Value(new Instrument(decode(fields[0]), decode(fields[1])),
                        Instant.parse(fields[2]), Instant.parse(fields[3]), new BigDecimal(fields[4])));
            }
            requireCanonical(payload, encode(version, values));
            store.appendAll(version, values);
        } else {
            throw new IllegalArgumentException("invalid factor batch kind");
        }
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void requireCanonical(String actual, String expected) {
        if (!actual.equals(expected)) throw new IllegalArgumentException("noncanonical factor batch");
    }
}
