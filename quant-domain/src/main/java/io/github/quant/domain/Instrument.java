package io.github.quant.domain;

import java.util.Objects;

/** A uniquely identified tradable security. */
public record Instrument(String symbol, String exchange) {
    public Instrument {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(exchange, "exchange");
        if (symbol.isBlank() || exchange.isBlank()) throw new IllegalArgumentException("symbol and exchange must be nonblank");
    }
}
