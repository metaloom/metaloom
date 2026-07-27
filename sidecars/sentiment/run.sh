#!/usr/bin/env bash
# Start the sentiment sidecar on port 9110 (the SentimentNode default).
# 9100 is taken by the TTS sidecar.
set -euo pipefail
cd "$(dirname "$0")"

HOST="${SENTIMENT_HOST:-0.0.0.0}"
PORT="${SENTIMENT_PORT:-9110}"

exec ./.venv/bin/uvicorn server:app --host "$HOST" --port "$PORT"
