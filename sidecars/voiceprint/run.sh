#!/usr/bin/env bash
# Start the voiceprint sidecar from the venv setup.sh created. Binds VOICEPRINT_HOST:VOICEPRINT_PORT
# (0.0.0.0:9150).
#
# 9100 is TTS, 9110 sentiment, 9120 depth, 9130 sam2, 9140 asr - 9150 is the next free port in the
# block of the analysis sidecars. server.py reads VOICEPRINT_HOST/VOICEPRINT_PORT itself, so this
# script and `python server.py` cannot disagree about the port.
set -euo pipefail
cd "$(dirname "$0")"

# One process on purpose: the models live in module globals.
exec ./.venv/bin/python server.py
