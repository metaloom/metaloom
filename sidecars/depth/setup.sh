#!/usr/bin/env bash
# Create the venv. Both checkpoints are ungated and are pulled from the Hugging Face
# Hub on the first request for their mode - no HF_TOKEN needed.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"

if [ ! -d .venv ]; then
  "$PYTHON" -m venv .venv
fi
./.venv/bin/pip install --upgrade pip
./.venv/bin/pip install -r requirements.txt

cat <<'EOF'

OK. Models download lazily on the first /v1/depth call for each mode:
  RELATIVE (default)  depth-anything/Depth-Anything-V2-Small-hf  (Apache-2.0)
  METRIC   (opt-in)   Intel/zoedepth-nyu-kitti                   (MIT)

Do NOT switch DEPTH_MODEL to the Base or Large variant of Depth Anything V2 - those
are CC-BY-NC-4.0 and cannot be used commercially. Small is the only Apache-2.0 member
of that family. A permissive step up in quality is Intel/dpt-large instead.
EOF
