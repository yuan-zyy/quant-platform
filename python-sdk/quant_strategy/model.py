"""Immutable inputs and outputs for a Python strategy callback."""

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from enum import Enum
from typing import Protocol


@dataclass(frozen=True)
class Instrument:
    symbol: str
    exchange: str


@dataclass(frozen=True)
class MarketClose:
    instrument: Instrument
    date: date
    event_time: str
    available_time: str
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: int


@dataclass(frozen=True)
class Position:
    instrument: Instrument
    quantity: int


@dataclass(frozen=True)
class Portfolio:
    cash: Decimal
    positions: tuple[Position, ...]


@dataclass(frozen=True)
class Context:
    processing_time: str
    portfolio: Portfolio


class Side(str, Enum):
    BUY = "BUY"
    SELL = "SELL"


@dataclass(frozen=True)
class Order:
    instrument: Instrument
    side: Side
    quantity: int
    limit: Decimal


class Strategy(Protocol):
    def __call__(self, event: MarketClose, context: Context) -> Sequence[Order]:
        """Return orders in submission order."""
        ...
