"""Strategy used by the Linux sandbox smoke test."""

import errno
import os
import socket
from pathlib import Path

from quant_strategy import Order, Side


def probe(event, context):
    assert os.geteuid() == 65532
    status = dict(line.split(":", 1) for line in Path("/proc/self/status").read_text().splitlines())
    assert int(status["CapEff"], 16) == 0
    assert status["NoNewPrivs"].strip() == "1"
    assert not Path("/var/run/docker.sock").exists()
    assert os.environ.get("QUANT_HOST_SECRET") is None

    try:
        Path("/var/tmp/quant-sandbox-should-not-exist").touch()
    except OSError as failure:
        assert failure.errno == errno.EROFS
    else:
        raise AssertionError("strategy filesystem was writable")

    with socket.socket() as conn:
        conn.settimeout(1)
        try:
            conn.connect(("192.0.2.1", 80))
        except OSError as failure:
            assert failure.errno == errno.ENETUNREACH
        else:
            raise AssertionError("sandbox could reach external network")

    scratch = Path("/tmp/strategy-smoke")
    scratch.write_text("scratch")
    scratch.unlink()
    if context.portfolio.positions:
        return []
    return [Order(event.instrument, Side.BUY, 100, event.close)]
