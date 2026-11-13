package io.github.quant.portfolio;

import io.github.quant.domain.Instrument;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public final class Portfolio {
    public record Snapshot(BigDecimal cash, Map<Instrument, Long> positions) {
        public Snapshot {
            positions = Map.copyOf(positions);
        }
    }

    private record Lot(LocalDate date, long quantity) {}

    private final Map<Instrument, ArrayDeque<Lot>> holdings = new HashMap<>();
    private BigDecimal cash;

    public Portfolio(BigDecimal startingCash) {
        if (startingCash.signum() <= 0) throw new IllegalArgumentException("positive cash required");
        cash = startingCash;
    }

    public long sellable(Instrument instrument, LocalDate date, boolean sameDayResale) {
        var lots = holdings.get(instrument);
        if (lots == null) return 0;
        return lots.stream()
                .filter(lot -> lot.date().isBefore(date) || (sameDayResale && lot.date().equals(date)))
                .mapToLong(Lot::quantity).reduce(0, Math::addExact);
    }

    public boolean buy(Instrument instrument, LocalDate date, long quantity, BigDecimal cost) {
        requirePositive(quantity, cost);
        if (cash.compareTo(cost) < 0) return false;
        var lots = holdings.computeIfAbsent(instrument, ignored -> new ArrayDeque<>());
        if (!lots.isEmpty() && date.isBefore(lots.getLast().date())) {
            throw new IllegalArgumentException("trade date moved backwards");
        }
        Math.addExact(total(lots), quantity);
        lots.addLast(new Lot(date, quantity));
        cash = cash.subtract(cost);
        return true;
    }

    public boolean sell(Instrument instrument, LocalDate date, boolean sameDayResale,
                        long quantity, BigDecimal proceeds) {
        if (quantity <= 0) throw new IllegalArgumentException("positive quantity required");
        BigDecimal balance = cash.add(proceeds);
        if (balance.signum() < 0 || sellable(instrument, date, sameDayResale) < quantity) return false;
        var lots = holdings.get(instrument);
        long remaining = quantity;
        while (remaining > 0) {
            Lot lot = lots.removeFirst();
            long consumed = Math.min(remaining, lot.quantity());
            remaining -= consumed;
            if (consumed < lot.quantity()) lots.addFirst(new Lot(lot.date(), lot.quantity() - consumed));
        }
        if (lots.isEmpty()) holdings.remove(instrument);
        cash = balance;
        return true;
    }

    public Snapshot snapshot() {
        Map<Instrument, Long> positions = new HashMap<>();
        holdings.forEach((instrument, lots) -> positions.put(instrument, total(lots)));
        return new Snapshot(cash, positions);
    }

    private static long total(ArrayDeque<Lot> lots) {
        return lots.stream().mapToLong(Lot::quantity).reduce(0, Math::addExact);
    }

    private static void requirePositive(long quantity, BigDecimal amount) {
        if (quantity <= 0 || amount.signum() <= 0) throw new IllegalArgumentException("invalid buy");
    }
}
