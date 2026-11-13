"""An example that targets one lot per instrument when affordable."""

from decimal import Decimal

from .model import Context, MarketClose, Order, Side


def buy_once(event: MarketClose, context: Context) -> tuple[Order, ...]:
    if any(position.instrument == event.instrument for position in context.portfolio.positions):
        return ()
    # Multiply by 100 by shifting the exponent.
    # Use the tuple constructor to avoid rounding by the decimal context.
    digits = event.close.as_tuple()
    cost = Decimal((digits.sign, digits.digits, digits.exponent + 2))
    if context.portfolio.cash < cost:
        return ()
    return (Order(event.instrument, Side.BUY, 100, event.close),)
