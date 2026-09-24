import sys
from pathlib import Path

# The sidecar is not a package: make `server` and `asr` importable from the tests.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
