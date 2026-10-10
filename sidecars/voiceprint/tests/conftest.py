import sys
from pathlib import Path

# `import server` and `from voiceprint import ...` from the sidecar root, wherever pytest is started.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
