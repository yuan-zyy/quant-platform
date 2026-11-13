package io.github.quant.paper;

import io.github.quant.backtest.BacktestEngine;
import io.github.quant.backtest.ExecutionCosts;
import io.github.quant.domain.Instrument;
import io.github.quant.domain.TradingRules;
import io.github.quant.market.DataFeed;
import io.github.quant.oms.LimitOrder;
import io.github.quant.portfolio.Portfolio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.CRC32C;

import static java.nio.file.StandardOpenOption.*;

/** Bounded, single-writer journal for a local simulated account. */
public final class JournaledPaperBroker implements AutoCloseable {
    private static final int MAGIC = 0x51504A31;
    private static final int MAX_RECORD = 65_536;
    private static final int MAX_COMMANDS = 10_000;
    private static final long MAX_FILE = 16 * 1024 * 1024;
    private final FileChannel channel;
    private final Supplier<PaperBroker> createBroker;
    private final List<Command<?>> commands = new ArrayList<>();
    private PaperBroker broker;

    private sealed interface Command<T> permits Submit, Cancel, Opening {
        T apply(PaperBroker broker);
    }

    private record Submit(String id, LimitOrder.Request request, Instant time)
            implements Command<LimitOrder.Snapshot> {
        public LimitOrder.Snapshot apply(PaperBroker broker) { return broker.submit(id, request, time); }
    }

    private record Cancel(String id, Instant time) implements Command<Optional<LimitOrder.Snapshot>> {
        public Optional<LimitOrder.Snapshot> apply(PaperBroker broker) { return broker.cancel(id, time); }
    }

    private record Opening(DataFeed.Open opening) implements Command<List<BacktestEngine.Fill>> {
        public List<BacktestEngine.Fill> apply(PaperBroker broker) { return broker.onOpen(opening); }
    }

    public static JournaledPaperBroker open(Path journal, BigDecimal cash, Map<Instrument, TradingRules> rules,
                                            ExecutionCosts costs) throws IOException {
        var copy = Map.copyOf(rules);
        var account = new PaperBroker(cash, copy, costs);
        var config = configuration(cash, copy, costs);
        FileChannel channel = FileChannel.open(journal, CREATE, READ, WRITE);
        try {
            if (channel.tryLock() == null) throw new IOException("paper journal is already locked");
            return new JournaledPaperBroker(channel, () -> new PaperBroker(cash, copy, costs), account, config);
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof OverlappingFileLockException) {
                throw new IOException("paper journal is already locked", failure);
            }
            throw failure;
        }
    }

    private JournaledPaperBroker(FileChannel channel, Supplier<PaperBroker> createBroker,
                                 PaperBroker broker, byte[] config) throws IOException {
        this.channel = channel;
        this.createBroker = createBroker;
        this.broker = broker;
        if (channel.size() > MAX_FILE) throw new IOException("paper journal exceeds size limit");
        if (channel.size() == 0) {
            append(config);
            return;
        }
        if (!Arrays.equals(config, readFrame(false))) throw new IOException("paper journal account configuration differs");
        byte[] frame;
        while ((frame = readFrame(true)) != null) {
            if (commands.size() == MAX_COMMANDS) throw new IOException("paper journal exceeds command limit");
            try {
                Command<?> command = decode(frame);
                command.apply(broker);
                commands.add(command);
            } catch (RuntimeException malformed) {
                throw new IOException("invalid paper journal command", malformed);
            }
        }
    }

    public synchronized LimitOrder.Snapshot submit(String id, LimitOrder.Request request, Instant time) throws IOException {
        return apply(new Submit(id, request, time));
    }

    public synchronized Optional<LimitOrder.Snapshot> cancel(String id, Instant time) throws IOException {
        return apply(new Cancel(id, time));
    }

    public synchronized List<BacktestEngine.Fill> onOpen(DataFeed.Open opening) throws IOException {
        return apply(new Opening(opening));
    }

    public synchronized Optional<LimitOrder.Snapshot> order(String id) {
        requireOpen();
        return broker.order(id);
    }

    public synchronized Portfolio.Snapshot portfolio() {
        requireOpen();
        return broker.portfolio();
    }

    private <T> T apply(Command<T> command) throws IOException {
        requireOpen();
        if (commands.contains(command)) return command.apply(broker);
        var candidate = createBroker.get();
        for (var previous : commands) previous.apply(candidate);
        T result = command.apply(candidate);
        // Unknown and already-terminal cancel attempts have no effect.
        if (command instanceof Cancel cancel && broker.order(cancel.id()).equals(result)) return result;
        if (commands.size() == MAX_COMMANDS) throw new IOException("paper journal exceeds command limit");
        try {
            append(encode(command));
        } catch (IOException failure) {
            try {
                close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        commands.add(command);
        broker = candidate;
        return result;
    }

    private void requireOpen() {
        if (!channel.isOpen()) throw new IllegalStateException("paper journal is closed");
    }

    private void append(byte[] payload) throws IOException {
        if (payload.length > MAX_RECORD || channel.position() + 12 + payload.length > MAX_FILE) {
            throw new IOException("paper journal record or file exceeds size limit");
        }
        var buffer = ByteBuffer.allocate(12 + payload.length).putInt(MAGIC).putInt(payload.length)
                .put(payload).putInt(checksum(payload)).flip();
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
    }

    private byte[] readFrame(boolean repairTail) throws IOException {
        long start = channel.position();
        long remaining = channel.size() - start;
        if (remaining == 0) return null;
        if (remaining < 8) return tornTail(start, repairTail);
        var header = read(8);
        int magic = header.getInt();
        int length = header.getInt();
        if (magic != MAGIC || length <= 0 || length > MAX_RECORD) throw new IOException("invalid paper journal frame");
        if (remaining < 12L + length) return tornTail(start, repairTail);
        var body = read(length + 4);
        byte[] payload = new byte[length];
        body.get(payload);
        if (body.getInt() != checksum(payload)) throw new IOException("paper journal checksum mismatch");
        return payload;
    }

    private byte[] tornTail(long start, boolean repair) throws IOException {
        if (!repair) throw new IOException("incomplete paper journal account configuration");
        channel.truncate(start);
        channel.force(true);
        return null;
    }

    private ByteBuffer read(int size) throws IOException {
        var buffer = ByteBuffer.allocate(size);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("paper journal changed during read");
        }
        return buffer.flip();
    }

    private static int checksum(byte[] bytes) {
        var crc = new CRC32C();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    private static byte[] configuration(BigDecimal cash, Map<Instrument, TradingRules> rules, ExecutionCosts costs)
            throws IOException {
        return bytes(out -> {
            out.writeUTF(cash.toString());
            out.writeUTF(costs.feeRate().toString());
            out.writeUTF(costs.minimumFee().toString());
            out.writeUTF(costs.slippageRate().toString());
            var instruments = rules.keySet().stream()
                    .sorted(Comparator.comparing(Instrument::exchange).thenComparing(Instrument::symbol)).toList();
            out.writeInt(instruments.size());
            for (var instrument : instruments) {
                writeInstrument(out, instrument);
                out.writeBoolean(rules.get(instrument).sameDayResale());
                out.writeLong(rules.get(instrument).buyLotSize());
            }
        });
    }

    private static byte[] encode(Command<?> command) throws IOException {
        return bytes(out -> {
            switch (command) {
                case Submit submit -> {
                    out.writeByte(1);
                    out.writeUTF(submit.id());
                    var request = submit.request();
                    writeInstrument(out, request.instrument());
                    out.writeUTF(request.side().name());
                    out.writeLong(request.quantity());
                    out.writeUTF(request.limit().toString());
                    out.writeUTF(submit.time().toString());
                }
                case Cancel cancel -> {
                    out.writeByte(2);
                    out.writeUTF(cancel.id());
                    out.writeUTF(cancel.time().toString());
                }
                case Opening event -> {
                    out.writeByte(3);
                    var opening = event.opening();
                    writeInstrument(out, opening.instrument());
                    out.writeUTF(opening.date().toString());
                    out.writeUTF(opening.eventTime().toString());
                    out.writeUTF(opening.availableTime().toString());
                    out.writeUTF(opening.price().toString());
                    out.writeLong(opening.availableQuantity());
                }
            }
        });
    }

    private static Command<?> decode(byte[] payload) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            Command<?> command = switch (in.readUnsignedByte()) {
                case 1 -> new Submit(in.readUTF(), new LimitOrder.Request(readInstrument(in),
                        LimitOrder.Side.valueOf(in.readUTF()), in.readLong(), new BigDecimal(in.readUTF())),
                        Instant.parse(in.readUTF()));
                case 2 -> new Cancel(in.readUTF(), Instant.parse(in.readUTF()));
                case 3 -> new Opening(new DataFeed.Open(readInstrument(in), LocalDate.parse(in.readUTF()),
                        Instant.parse(in.readUTF()), Instant.parse(in.readUTF()), new BigDecimal(in.readUTF()),
                        in.readLong()));
                default -> throw new IOException("invalid paper journal command kind");
            };
            if (in.read() != -1) throw new IOException("unexpected bytes in paper journal command");
            return command;
        }
    }

    private static void writeInstrument(DataOutputStream out, Instrument instrument) throws IOException {
        out.writeUTF(instrument.symbol());
        out.writeUTF(instrument.exchange());
    }

    private static Instrument readInstrument(DataInputStream in) throws IOException {
        return new Instrument(in.readUTF(), in.readUTF());
    }

    @FunctionalInterface
    private interface WritePayload {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] bytes(WritePayload writer) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            writer.write(out);
        }
        return bytes.toByteArray();
    }

    @Override public synchronized void close() throws IOException {
        channel.close();
    }
}
