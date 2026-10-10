#!/usr/bin/env bash
# Create the venv for running the voiceprint sidecar without a container.
#
# torch/torchaudio are installed FIRST, matched to your CUDA, because requirements.txt deliberately
# leaves them out (a CUDA mismatch fails late and confusingly). Override TORCH_INDEX_URL for a
# different CUDA, or set it empty for the CPU wheels from PyPI.
#
# The container (./container.sh build) is the supported deployment; this is for development.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
TORCH_INDEX_URL="${TORCH_INDEX_URL:-https://download.pytorch.org/whl/cu128}"

if [ ! -d .venv ]; then
  if command -v uv >/dev/null 2>&1; then
    uv venv --python 3.12 .venv
  else
    "$PYTHON" -m venv .venv
  fi
fi
./.venv/bin/python -m pip install --upgrade pip
if [ -n "$TORCH_INDEX_URL" ]; then
  ./.venv/bin/pip install torch torchaudio --index-url "$TORCH_INDEX_URL"
else
  ./.venv/bin/pip install torch torchaudio
fi
./.venv/bin/pip install -r requirements.txt

cat <<'MSG'

OK. Weights download on first use:
  redimnet2    github.com/PalabraAI/redimnet2 release b6-vox2-lm.pt    ~50 MB  -> $TORCH_HOME/hub/checkpoints
  wespeaker    Wespeaker/wespeaker-voxceleb-resnet34-LM (onnx)         ~25 MB  -> HF cache
  speechbrain  speechbrain/spkrec-ecapa-voxceleb                       ~80 MB  -> HF cache

Also needed on the host: libsndfile, and ffmpeg for anything that is not WAV/FLAC/OGG/MP3.

Then:  ./run.sh                   # serves on :9150
       .venv/bin/python -m pytest tests -q --ignore=tests/test_live.py
MSG
