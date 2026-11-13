# Sprint 1 implementation status

Implemented: immutable Instrument, validated daily Bar, deterministic event loop sorted by availability/priority/sequence, and a minimal buy-and-hold return calculator with JUnit tests.

Not implemented: order matching, portfolio ledger, T+1 settlement, fees, corporate actions, performance metrics, or full event-driven backtesting. This prototype must not be used for investment decisions.

Acceptance: `mvn -B -ntp verify` on Java 21 must pass before Sprint 1 is considered complete.
