#!/usr/bin/env bash
# Start the ASR sidecar from the venv setup.sh created. Binds ASR_HOST:ASR_PORT (0.0.0.0:9140).
#
# 9100 is TTS, 9110 sentiment, 9120 depth, 9130 sam2 - 9140 is the next free port in the block of
# the analysis sidecars. server.py reads ASR_HOST/ASR_PORT itself, so this script and
# `python server.py` cannot disagree about the port.
set -euo pipefail
cd "$(dirname "$0")"

# CTranslate2 (whisper) finds cuBLAS/cuDNN only on the library path. In a venv they come from the
# nvidia-* wheels in requirements.txt; the container gets them from its base image instead.
SITE="$(./.venv/bin/python -c 'import site; print(site.getsitepackages()[0])')"
for lib in "$SITE"/nvidia/cublas/lib "$SITE"/nvidia/cudnn/lib; do
  [ -d "$lib" ] && export LD_LIBRARY_PATH="$lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
done

# One process on purpose: the models live in module globals.
exec ./.venv/bin/python server.py
