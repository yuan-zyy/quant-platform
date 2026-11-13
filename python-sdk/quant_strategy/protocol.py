"""Version 1 UTF-8 JSON Lines adapter."""

from collections.abc import Sequence
from datetime import date, datetime
from decimal import Decimal
import json
import re
import sys
from typing import BinaryIO
from unicodedata import category

from .model import Context, Instrument, MarketClose, Order, Portfolio, Position, Side, Strategy

VERSION = 1
MAX_LINE_BYTES = 1024 * 1024
MAX_DECIMAL_LENGTH = 128
MAX_QUANTITY = (1 << 63) - 1

_DECIMAL = re.compile(r"[+-]?[0-9]+(?:\.[0-9]+)?")
_INTEGER = re.compile(r"(?:0|[1-9][0-9]{0,18})")
_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")
_INSTANT = re.compile(r"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?:\.([0-9]{1,9}))?Z")
_ENCODER = json.JSONEncoder(ensure_ascii=False, allow_nan=False, separators=(",", ":"))


def _object(value: object, *fields: str) -> dict:
    if not isinstance(value, dict) or value.keys() != set(fields):
        raise ValueError("invalid object fields")
    return value


def _unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError(f"invalid JSON constant {value}")


def _is_java_whitespace(char: str) -> bool:
    return char in "\t\n\v\f\r\x1c\x1d\x1e\x1f" or (
        char not in "\u00a0\u2007\u202f" and category(char) in ("Zs", "Zl", "Zp")
    )


def _identity(value: object) -> str:
    if not isinstance(value, str) or len(value) > MAX_LINE_BYTES or all(_is_java_whitespace(char) for char in value):
        raise ValueError("expected a nonblank string")
    value.encode("utf-8")
    return value


def _decimal(value: object, *, allow_zero: bool = False) -> Decimal:
    if not isinstance(value, str) or len(value) > MAX_DECIMAL_LENGTH or not _DECIMAL.fullmatch(value):
        raise ValueError("invalid decimal string")
    result = Decimal(value)
    if result < 0 or (result == 0 and not allow_zero):
        raise ValueError("invalid decimal range")
    return result


def _quantity(value: object, *, allow_zero: bool = False) -> int:
    if not isinstance(value, str) or not _INTEGER.fullmatch(value):
        raise ValueError("invalid quantity string")
    result = int(value)
    if result > MAX_QUANTITY or (result == 0 and not allow_zero):
        raise ValueError("invalid quantity range")
    return result


def _instant(value: object) -> tuple[datetime, int]:
    match = _INSTANT.fullmatch(value) if isinstance(value, str) else None
    if match is None:
        raise ValueError("invalid UTC instant")
    return datetime.fromisoformat(match[1]), int((match[2] or "").ljust(9, "0"))


def _instrument(value: object) -> Instrument:
    fields = _object(value, "symbol", "exchange")
    return Instrument(_identity(fields["symbol"]), _identity(fields["exchange"]))


def _decode(request: dict) -> tuple[MarketClose, Context]:
    _object(request, "version", "requestId", "event", "portfolio")
    version = request["version"]
    if type(version) is not int or version != VERSION:
        raise ValueError("unsupported protocol version")

    event = _object(request["event"], "instrument", "date", "eventTime", "availableTime",
                    "processingTime", "open", "high", "low", "close", "volume")
    if not isinstance(event["date"], str) or not _DATE.fullmatch(event["date"]):
        raise ValueError("invalid session date")
    event_date = date.fromisoformat(event["date"])
    event_time = _instant(event["eventTime"])
    available_time = _instant(event["availableTime"])
    processing_time = _instant(event["processingTime"])
    if not event_time <= available_time <= processing_time:
        raise ValueError("nonmonotonic event timestamps")
    opening, high, low, close = (_decimal(event[name]) for name in ("open", "high", "low", "close"))
    if not (low <= opening <= high and low <= close <= high):
        raise ValueError("invalid OHLC range")
    market = MarketClose(_instrument(event["instrument"]), event_date, event["eventTime"],
                         event["availableTime"], opening, high, low, close,
                         _quantity(event["volume"], allow_zero=True))

    account = _object(request["portfolio"], "cash", "positions")
    if not isinstance(account["positions"], list):
        raise ValueError("positions must be an array")
    positions = []
    seen = set()
    for value in account["positions"]:
        position = _object(value, "instrument", "quantity")
        instrument = _instrument(position["instrument"])
        if instrument in seen:
            raise ValueError("duplicate position")
        seen.add(instrument)
        positions.append(Position(instrument, _quantity(position["quantity"])))
    portfolio = Portfolio(_decimal(account["cash"], allow_zero=True), tuple(positions))
    return market, Context(event["processingTime"], portfolio)


def _order(value: object) -> dict:
    if not isinstance(value, Order) or not isinstance(value.instrument, Instrument):
        raise ValueError("strategy must return Order objects")
    if not isinstance(value.side, Side):
        raise ValueError("order side must be a Side")
    if type(value.quantity) is not int or not 0 < value.quantity <= MAX_QUANTITY:
        raise ValueError("invalid order quantity")
    price = value.limit
    if not isinstance(price, Decimal) or not price.is_finite() or price <= 0:
        raise ValueError("order limit must be a positive finite Decimal")
    # Bound fixed-point expansion before formatting a strategy-provided Decimal.
    if not -MAX_DECIMAL_LENGTH <= price.adjusted() < MAX_DECIMAL_LENGTH:
        raise ValueError("order limit exceeds decimal length")
    if price.as_tuple().exponent < -MAX_DECIMAL_LENGTH:
        raise ValueError("order limit exceeds decimal length")
    limit = format(price, "f")
    if len(limit) > MAX_DECIMAL_LENGTH:
        raise ValueError("order limit exceeds decimal length")
    return {
        "instrument": {"symbol": _identity(value.instrument.symbol), "exchange": _identity(value.instrument.exchange)},
        "side": value.side.value,
        "quantity": str(value.quantity),
        "limit": limit,
    }


def _encode(value: dict) -> bytes:
    response = bytearray()
    for piece in _ENCODER.iterencode(value):
        response.extend(piece.encode("utf-8"))
        if len(response) > MAX_LINE_BYTES:
            raise ValueError("response exceeds line limit")
    return bytes(response) + b"\n"


def _error(request_id: str, code: str, message: str) -> bytes:
    return _encode({"version": VERSION, "requestId": request_id, "error": {"code": code, "message": message}})


def _respond(request: dict, request_id: str, strategy: Strategy) -> bytes:
    try:
        event, context = _decode(request)
    except (ValueError, OverflowError):
        return _error(request_id, "INVALID_REQUEST", "Request does not match protocol v1")
    try:
        result = strategy(event, context)
    except Exception:
        return _error(request_id, "STRATEGY_ERROR", "Strategy callback raised an exception")
    try:
        if not isinstance(result, Sequence) or isinstance(result, (str, bytes, bytearray)):
            raise ValueError("strategy must return a sequence")
        envelope = _encode({"version": VERSION, "requestId": request_id})
        response = bytearray(envelope[:-2])
        response.extend(b',"orders":[')
        for index, order in enumerate(result):
            if index:
                response.append(ord(","))
            response.extend(_encode(_order(order))[:-1])
            if len(response) + 2 > MAX_LINE_BYTES:
                raise ValueError("response exceeds line limit")
        return bytes(response) + b"]}\n"

    except Exception:
        return _error(request_id, "INVALID_RESULT", "Strategy result does not match protocol v1")


def serve(input_stream: BinaryIO, output_stream: BinaryIO, strategy: Strategy) -> int:
    """Serve complete LF-terminated requests until EOF or an uncorrelatable frame."""
    while True:
        line = input_stream.readline(MAX_LINE_BYTES + 2)
        if not line:
            return 0
        if len(line) > MAX_LINE_BYTES + 1 or not line.endswith(b"\n"):
            print("Invalid strategy request frame", file=sys.stderr)
            return 2
        try:
            payload = line[:-1].removesuffix(b"\r")
            if b"\r" in payload:
                raise ValueError("unexpected carriage return")
            request = json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_object,
                                 parse_constant=_reject_constant)
            if not isinstance(request, dict):
                raise ValueError("expected an object")
            request_id = _identity(request.get("requestId"))
            response = _respond(request, request_id, strategy)
        except (ValueError, RecursionError):
            print("Invalid or uncorrelatable strategy request", file=sys.stderr)
            return 2
        output_stream.write(response)
        output_stream.flush()
