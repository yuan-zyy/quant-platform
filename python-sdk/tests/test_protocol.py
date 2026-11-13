from datetime import date
from decimal import Decimal
from io import BytesIO, StringIO
from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr

from quant_strategy import Order, Side
from quant_strategy.examples import buy_once
from quant_strategy.protocol import MAX_LINE_BYTES, serve


def request(request_id: str = "回测-1") -> dict:
    return {
        "version": 1,
        "requestId": request_id,
        "event": {
            "instrument": {"symbol": "600000", "exchange": "SSE"},
            "date": "2026-01-05",
            "eventTime": "2026-01-05T07:00:00.000000001Z",
            "availableTime": "2026-01-05T07:00:00.000000002Z",
            "processingTime": "2026-01-05T07:00:00.000000003Z",
            "open": "10.00",
            "high": "12",
            "low": "9",
            "close": "11.00",
            "volume": "9223372036854775807",
        },
        "portfolio": {"cash": "10000", "positions": []},
    }


def wire(value: dict) -> bytes:
    return json.dumps(value, ensure_ascii=False).encode("utf-8") + b"\n"


class ProtocolTest(unittest.TestCase):
    def serve(self, data, strategy):
        output = BytesIO()
        with redirect_stderr(StringIO()):
            status = serve(BytesIO(data), output, strategy)
        return status, [json.loads(line) for line in output.getvalue().splitlines()]

    def test_preserves_numbers_timestamps_and_submission_order(self):
        def strategy(event, context):
            self.assertEqual(event.date, date(2026, 1, 5))
            self.assertEqual(event.volume, (1 << 63) - 1)
            self.assertEqual(event.event_time, "2026-01-05T07:00:00.000000001Z")
            self.assertEqual(context.processing_time, "2026-01-05T07:00:00.000000003Z")
            self.assertEqual(context.portfolio.cash, Decimal("10000"))
            return (Order(event.instrument, Side.SELL, 1, Decimal("1E-7")),
                    Order(event.instrument, Side.BUY, 100, event.close))

        status, [response] = self.serve(wire(request()), strategy)
        self.assertEqual(status, 0)
        self.assertEqual(response["requestId"], "回测-1")
        self.assertEqual(response["version"], 1)
        self.assertEqual([order["side"] for order in response["orders"]], ["SELL", "BUY"])
        self.assertEqual([order["limit"] for order in response["orders"]], ["0.0000001", "11.00"])

    def test_rejects_invalid_requests_without_dispatching(self):
        def unreachable(*args):
            self.fail("strategy was invoked for invalid input")

        bad_version = request("version")
        bad_version["version"] = True
        bad_time = request("time")
        bad_time["event"]["availableTime"] = "2026-01-05T07:00:00Z"
        bad_quantity = request("quantity")
        bad_quantity["event"]["volume"] = "9223372036854775808"
        bad_price = request("price")
        bad_price["event"]["open"] = "1e1"
        bad_position = request("position")
        position = {"instrument": bad_position["event"]["instrument"], "quantity": "100"}
        bad_position["portfolio"]["positions"] = [position, position]
        inputs = [bad_version, bad_time, bad_quantity, bad_price, bad_position]
        status, responses = self.serve(b"".join(map(wire, inputs)), unreachable)
        self.assertEqual(status, 0)
        self.assertEqual([item["requestId"] for item in responses], [item["requestId"] for item in inputs])
        self.assertTrue(all(item["error"]["code"] == "INVALID_REQUEST" for item in responses))

    def test_reports_callback_and_output_errors_and_continues(self):
        outcomes = iter([None, Decimal("1E-1000000"), Decimal("10")])

        def strategy(event, context):
            price = next(outcomes)
            if price is None:
                raise RuntimeError("sensitive details")
            return (Order(event.instrument, Side.BUY, 100, price),)

        inputs = b"".join(wire(request(str(index))) for index in range(3))
        status, responses = self.serve(inputs, strategy)
        self.assertEqual(status, 0)
        self.assertEqual(responses[0]["error"]["code"], "STRATEGY_ERROR")
        self.assertNotIn("sensitive details", str(responses[0]))
        self.assertEqual(responses[1]["error"]["code"], "INVALID_RESULT")
        self.assertEqual(responses[2]["orders"][0]["quantity"], "100")

    def test_closes_on_invalid_frames(self):
        invalid_inputs = [
            b'{"requestId":"a","requestId":"b"}\n',
            b'{"requestId":"a","version":NaN}\n',
            b'{"requestId":"\\ud800"}\n',
            b'\xff\n',
            b" " * (MAX_LINE_BYTES + 1) + b"\n",
            wire(request())[:-1],
        ]
        for value in invalid_inputs:
            with self.subTest(length=len(value)):
                self.assertEqual(self.serve(value, buy_once), (2, []))

    def test_rejects_oversized_results_and_accepts_unicode_ids(self):
        order_count = 20_000

        def many_orders(event, context):
            order = Order(event.instrument, Side.BUY, 100, event.close)
            return (order,) * order_count

        status, [response] = self.serve(wire(request("\u00a0")), many_orders)
        self.assertEqual(status, 0)
        self.assertEqual(response["requestId"], "\u00a0")
        self.assertEqual(response["error"]["code"], "INVALID_RESULT")

    def test_example_buys_only_when_affordable_and_unheld(self):
        starting = request("starting")
        held = request("held")
        held["portfolio"]["positions"] = [{"instrument": held["event"]["instrument"], "quantity": "100"}]
        insufficient = request("insufficient")
        insufficient["portfolio"]["cash"] = "1099.99999999999999999999999999999999"
        status, responses = self.serve(b"".join(map(wire, [starting, held, insufficient])), buy_once)
        self.assertEqual(status, 0)
        self.assertEqual(responses[0]["orders"][0]["quantity"], "100")
        self.assertEqual([response["orders"] for response in responses[1:]], [[], []])

    def test_cli_keeps_strategy_prints_off_protocol_stdout(self):
        with tempfile.TemporaryDirectory() as directory:
            strategy_module = Path(directory) / "test_strategy.py"
            strategy_module.write_text(
                'print("importing")\ndef on_close(event, context):\n    print("running")\n    return ()\n',
                encoding="utf-8",
            )
            result = subprocess.run(
                [sys.executable, "-m", "quant_strategy", "test_strategy:on_close"],
                env={**os.environ, "PYTHONPATH": directory},
                input=wire(request()), capture_output=True, timeout=10, check=True,
            )
        self.assertEqual(json.loads(result.stdout)["orders"], [])
        self.assertIn(b"importing", result.stderr)
        self.assertIn(b"running", result.stderr)


if __name__ == "__main__":
    unittest.main()
