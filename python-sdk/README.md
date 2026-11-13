# Python strategy SDK

Requires Python 3.10 or newer. The SDK has no runtime dependencies. Run these commands from the `python-sdk` directory.

```console
python -m unittest discover -s tests -v
python -m quant_strategy quant_strategy.examples:buy_once
```

The second command launches a strategy worker that waits for version 1 JSON Lines requests on standard input. Run it under the Java worker, which handles the protocol and subprocess lifecycle. To use your own strategy, replace the entrypoint with `module:attribute`. The module must be importable in the worker's Python environment. Each worker loads the callable once and invokes it once per close.

```python
from quant_strategy import Context, MarketClose, Order, Side

def on_close(event: MarketClose, context: Context) -> tuple[Order, ...]:
    if context.portfolio.positions or context.portfolio.cash < 1000:
        return ()
    return (Order(event.instrument, Side.BUY, 100, event.close),)
```

Callback inputs are immutable. Session dates use `datetime.date`, prices and cash use `Decimal`, quantities use integers, and timestamps retain the original UTC text and nanosecond precision. The worker returns orders in the strategy's submission order. It sends a correlated error for invalid requests, callback exceptions, or invalid strategy results. It exits on malformed or oversized frames, or on EOF. The protocol uses UTF-8, LF-terminated frames of up to 1 MiB excluding LF. Ordinary Python `print` output goes to standard error.

The bundled `buy_once` example requests a 100-unit buy at the observed close if cash can cover the limit notional and there is no position in that instrument. An unfilled order may cause a later callback to request another order. The replay engine determines fills and applies the configured fees and rules.

This SDK assumes trusted local strategy code. Running it as a Python subprocess provides process separation. A deployment that runs tenant-supplied strategies needs operating-system isolation for filesystem, network and resource access.
