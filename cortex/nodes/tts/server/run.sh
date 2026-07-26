#!/usr/bin/env bash
# Start the TTS sidecar on port 9100 (the TtsNode default).
set -euo pipefail
cd "$(dirname "$0")"

HOST="${TTS_HOST:-0.0.0.0}"
PORT="${TTS_PORT:-9100}"

exec ./.venv/bin/uvicorn server:app --host "$HOST" --port "$PORT"
