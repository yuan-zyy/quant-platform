package io.github.quant.market.factor;

import java.time.Instant;

/** Point-in-time reads against one factor journal with stable, addressable generations. */
public interface FactorSnapshotReader {
    long generation();

    FactorStore.Snapshot snapshot(FactorStore.FactorVersion factor, Instant asOf, long generation);
}
