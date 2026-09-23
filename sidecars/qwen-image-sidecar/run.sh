#!/usr/bin/env bash
# Start the Qwen-Image-2.1 sidecar on port 9230.
# 9100 is taken by TTS, 9110 by sentiment, 9120 by depth, 9130 by sam2, 9200 by
# ideogram, 9210 by mage-flow and 9220 by ltx2 - 9230 is the next free port and keeps
# the third image backend alongside the other two, so the imagegen node can be pointed
# at any of them by changing its `port` option alone.
#
# Measured VRAM is ~46 GB at the default output_resolution of 1024 and ~66 GB at 2048 -
# well above the 33 GB of weights, because activations and the KV cache are not returned
# between requests. Pin an 80 GB card (A100 80 GB, H200); on a smaller one set
# QWENIMAGE_OFFLOAD=1.
set -euo pipefail
cd "$(dirname "$0")"

HOST="${QWENIMAGE_HOST:-0.0.0.0}"
PORT="${QWENIMAGE_PORT:-9230}"

# One resident model, one generation at a time (see server.py) - a second uvicorn
# worker would try to load its own 33 GB copy and OOM the card.
exec ./.venv/bin/uvicorn server:app --host "$HOST" --port "$PORT" --workers 1
