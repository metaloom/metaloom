#!/usr/bin/env bash
# Start the SAM 2 sidecar on port 9130 (the Sam2Node default).
# 9100 is taken by TTS, 9110 by sentiment, 9120 by depth - 9130 is the next free port
# and keeps it in the 91xx analysis band rather than the 92xx generative one.
set -euo pipefail
cd "$(dirname "$0")"

HOST="${SAM2_HOST:-0.0.0.0}"
PORT="${SAM2_PORT:-9130}"

# --workers 1: segment-everything at points_per_side=32 is 1024 forward passes and the
# video predictor holds a per-request memory bank. A second worker does not make the
# card faster, it makes it run out of memory. server.py also holds a GPU lock, because
# FastAPI dispatches sync handlers to a threadpool even with one worker.
exec ./.venv/bin/uvicorn server:app --host "$HOST" --port "$PORT" --workers 1
