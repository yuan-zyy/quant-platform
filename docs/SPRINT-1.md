# Sprint 1 daily backtest

Use `BacktestEngine.run` with a `DataFeed`, initial cash, per-instrument `TradingRules`, execution costs, and a strategy callback. `HistoricalDataFeed` replays in-memory sessions with separate opening observations and close publication times. The existing `buyAndHold` calculation is also available.

- The event loop dispatches by availability time, then opening before close, then a canonical order by event time, exchange, symbol and session date. The virtual clock supplies processing time. The strategy sees each close at publication and receives an immutable portfolio snapshot.
- Limit orders are evaluated at a subsequent opening for their instrument, with submission time strictly earlier than that opening's event time. An explicit opening quantity caps fills across orders in submission order. Buy fills are rounded down to the configured lot size. Unfilled quantity can carry forward until the replay ends. Insufficient buying power or sellable inventory terminates the remaining order at the attempted fill.
- The long-only ledger tracks cash and lots by acquisition date. T+1 eligibility uses the next observed trading session. The security presets distinguish A-shares, domestic equity ETFs, same-day ETFs and convertible bonds. Pass explicit instrument rules when lot size or resale eligibility differs.
- The model applies adverse price slippage before checking a limit and charges a configurable per-fill fee. Fees round to two decimal places. Equity is sampled after each observation using the newest known mark for each position. The result includes total return, maximum drawdown on that curve, fees, turnover in currency and fill count.
- Opening quantity is supplied separately from total daily volume. For replay, opening liquidity and fee settings are model inputs. This daily-bar model uses opening-price execution and immediate simulated cash booking. It does not model intraday queues, exchange price limits, broker cash settlement, corporate actions or venue-specific fee schedules.

Verify with Java 21 and `mvn -B -ntp clean verify`.
