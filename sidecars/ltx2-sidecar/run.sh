#!/usr/bin/env bash
# Start the LTX-2 video sidecar on port 9220.
# 9100 is taken by TTS, 9110 by sentiment, 9120 by depth, 9200 by ideogram, 9210 by
# mage-flow — 9220 is the next free port and keeps the video sidecar alongside the two
# image ones.
#
# LTX-2 is a 19B audio-video model; a single generation can consume the whole card.
# --workers 1 keeps one resident pipeline and one generation at a time (see server.py).
set -euo pipefail
cd "$(dirname "$0")"

HOST="${LTX2_HOST:-0.0.0.0}"
PORT="${LTX2_PORT:-9220}"

exec ./.venv/bin/uvicorn server:app --host "$HOST" --port "$PORT" --workers 1
