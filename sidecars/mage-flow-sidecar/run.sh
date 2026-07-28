#!/usr/bin/env bash
# Start the Mage-Flow sidecar on port 9210.
# 9100 is taken by the TTS sidecar, 9110 by sentiment, 9120 by depth, 9200 by the
# ideogram-sidecar - 9210 lets both image sidecars run side by side, so the imagegen
# node can be pointed at either by changing its `port` option alone.
#
# The bf16 weights are 17.5 GB and peak around 18-20 GB at 1024x1024, so pin a card
# with >=24 GB. A 12 GB GPU cannot hold this model at all.
set -euo pipefail
cd "$(dirname "$0")"

HOST="${MAGEFLOW_HOST:-0.0.0.0}"
PORT="${MAGEFLOW_PORT:-9210}"

# One resident model, one generation at a time (see server.py) - a second uvicorn
# worker would try to load its own 17.5 GB copy and OOM the card.
exec ./.venv/bin/uvicorn server:app --host "$HOST" --port "$PORT" --workers 1
