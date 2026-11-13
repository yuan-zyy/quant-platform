import argparse
from contextlib import redirect_stdout
from importlib import import_module
import sys

from .protocol import serve


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a quant-platform Python strategy over JSONL.")
    parser.add_argument("entrypoint", help="Importable strategy callable as module:attribute")
    args = parser.parse_args()
    module, separator, attribute = args.entrypoint.partition(":")
    if not separator or not module or not attribute:
        parser.error("entrypoint must use module:attribute")

    wire_output = sys.stdout.buffer
    with redirect_stdout(sys.stderr):
        try:
            strategy = getattr(import_module(module), attribute)
            if not callable(strategy):
                raise TypeError("entrypoint must be callable")
        except Exception as exc:
            print(f"Could not load strategy: {type(exc).__name__}", file=sys.stderr)
            return 2
        return serve(sys.stdin.buffer, wire_output, strategy)


if __name__ == "__main__":
    raise SystemExit(main())
