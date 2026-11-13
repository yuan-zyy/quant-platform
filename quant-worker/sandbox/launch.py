"""Start the image-provided SDK with isolated Python settings."""

import sys

sys.path.insert(0, "/opt/strategy")

from quant_strategy.__main__ import main

raise SystemExit(main())
