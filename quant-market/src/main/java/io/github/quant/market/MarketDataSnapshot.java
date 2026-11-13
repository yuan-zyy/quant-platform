package io.github.quant.market;

import java.time.Instant;
import java.util.List;

/** Immutable view of a dataset generation at a market publication cutoff. */
public final class MarketDataSnapshot {
    private final String datasetVersion;
    private final Instant asOf;
    private final long generation;
    private final List<DataFeed.Observation> observations;
    private final List<DataFeed.Observation> firstPublications;

    MarketDataSnapshot(String datasetVersion, Instant asOf, long generation,
                       List<DataFeed.Observation> observations, List<DataFeed.Observation> firstPublications) {
        this.datasetVersion = datasetVersion;
        this.asOf = asOf;
        this.generation = generation;
        this.observations = List.copyOf(observations);
        this.firstPublications = List.copyOf(firstPublications);
    }

    public String datasetVersion() { return datasetVersion; }
    public Instant asOf() { return asOf; }
    public long generation() { return generation; }

    /** Latest publication per instrument, session and observation type visible at the cutoff. */
    public List<DataFeed.Observation> observations() { return observations; }

    /**
     * Replays the original publication of each visible observation. The daily backtest consumes
     * one open and one close per session and cannot apply intraday revision events.
     */
    public DataFeed replayFeed() {
        return firstPublications::forEach;
    }
}
