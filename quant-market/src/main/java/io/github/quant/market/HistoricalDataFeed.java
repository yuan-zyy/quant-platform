package io.github.quant.market;

import io.github.quant.domain.Instrument;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Replay a fixed daily dataset, with explicit opening liquidity and close publication time. */
public final class HistoricalDataFeed implements DataFeed {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    public record Session(Instrument instrument, Bar bar, long openingQuantity, Instant closeAvailableTime) {
        public Session {
            Objects.requireNonNull(instrument);
            Objects.requireNonNull(bar);
            Objects.requireNonNull(closeAvailableTime);
            if (openingQuantity < 0) throw new IllegalArgumentException("negative opening quantity");
        }
    }

    private final List<Session> sessions;

    public HistoricalDataFeed(List<Session> sessions) {
        this.sessions = List.copyOf(sessions);
    }

    public static Instant closeTime(Bar bar) {
        return bar.date().atTime(15, 0).atZone(MARKET_ZONE).toInstant();
    }

    @Override
    public void replay(Consumer<Observation> consumer) {
        for (Session session : sessions) {
            Bar bar = session.bar();
            Instant open = bar.date().atTime(LocalTime.of(9, 30)).atZone(MARKET_ZONE).toInstant();
            consumer.accept(new Open(session.instrument(), bar.date(), open, open,
                    bar.open(), session.openingQuantity()));
            consumer.accept(new Close(session.instrument(), closeTime(bar), session.closeAvailableTime(), bar));
        }
    }
}
