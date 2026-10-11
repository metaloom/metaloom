#!/usr/bin/env bash
# Start the TTS sidecar on port 9100 (the TtsNode default).
#
# server.py reads TTS_HOST/TTS_PORT itself, so this script and `python server.py` cannot disagree
# about the port. One process, one worker: each uvicorn worker would load its own full copy of the
# engine onto the same GPU.
set -euo pipefail
cd "$(dirname "$0")"

exec ./.venv/bin/python server.py
