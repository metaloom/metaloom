#!/usr/bin/env bash
# Create the venv for running the ASR sidecar without a container.
#
# torch is installed FIRST, matched to your CUDA, because requirements.txt deliberately leaves it
# out (a CUDA mismatch fails late and confusingly). Override TORCH_INDEX_URL for a different CUDA.
#
# The container (./container.sh build) is the supported deployment; this is for development.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
TORCH_INDEX_URL="${TORCH_INDEX_URL:-https://download.pytorch.org/whl/cu128}"

if [ ! -d .venv ]; then
  "$PYTHON" -m venv .venv
fi
./.venv/bin/pip install --upgrade pip
if [ -n "$TORCH_INDEX_URL" ]; then
  ./.venv/bin/pip install torch --index-url "$TORCH_INDEX_URL"
fi
./.venv/bin/pip install -r requirements.txt

cat <<'EOF'

OK. Models download on first use into the HF cache:
  whisper   mobiuslabsgmbh/faster-whisper-large-v3-turbo        ~1.6 GB
  parakeet  csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3    ~2.5 GB  (copied to $HF_HOME/asr-sidecar/)
  voxtral   mistralai/Voxtral-Mini-4B-Realtime-2602             ~8.9 GB

Also needed on the host: libsndfile, and ffmpeg for video uploads (mp4/mkv/webm).

Then:  ./run.sh                   # serves on :9140
       .venv/bin/python -m pytest tests -q --ignore=tests/test_live.py
EOF
