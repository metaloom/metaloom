#!/usr/bin/env bash
# Start the depth sidecar on port 9120 (the DepthmapNode default).
# 9100 is taken by the TTS sidecar, 9110 by sentiment, 9200 by imagegen.
set -euo pipefail
cd "$(dirname "$0")"

HOST="${DEPTH_HOST:-0.0.0.0}"
PORT="${DEPTH_PORT:-9120}"

exec ./.venv/bin/uvicorn server:app --host "$HOST" --port "$PORT"
