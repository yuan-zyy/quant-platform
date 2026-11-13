package io.github.quant.market;

import io.github.quant.domain.Instrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Consumer;

@FunctionalInterface
public interface DataFeed {
    void replay(Consumer<Observation> consumer);

    sealed interface Observation permits Open, Close {
        Instrument instrument();
        LocalDate date();
        Instant eventTime();
        Instant availableTime();
    }

    /** Opening price and a supplied liquidity estimate, in instrument units. */
    record Open(Instrument instrument, LocalDate date, Instant eventTime, Instant availableTime,
                BigDecimal price, long availableQuantity) implements Observation {
        public Open {
            validate(instrument, date, eventTime, availableTime);
            if (price.signum() <= 0 || availableQuantity < 0) {
                throw new IllegalArgumentException("invalid opening price or quantity");
            }
        }
    }

    record Close(Instrument instrument, Instant eventTime, Instant availableTime, Bar bar)
            implements Observation {
        public Close {
            Objects.requireNonNull(bar);
            validate(instrument, bar.date(), eventTime, availableTime);
        }

        @Override
        public LocalDate date() {
            return bar.date();
        }
    }

    private static void validate(Instrument instrument, LocalDate date, Instant eventTime, Instant availableTime) {
        Objects.requireNonNull(instrument);
        Objects.requireNonNull(date);
        if (availableTime.isBefore(eventTime)) throw new IllegalArgumentException("observation available before event");
    }
}
