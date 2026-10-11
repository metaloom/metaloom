#!/usr/bin/env bash
# Create the venv and fetch the Kokoro weights. The Orpheus/SNAC weights are pulled from the
# Hugging Face Hub on the first lang=de request.
#
# KARTOFFELBOX=1 ./setup.sh  also installs the Chatterbox stack for TTS_ENGINE_DE=kartoffelbox
# (the better German voice). It is opt-in because it is a ~1 GB dependency set that a process
# running the default Orpheus engine never imports.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"

if [ ! -d .venv ]; then
  "$PYTHON" -m venv .venv
fi
./.venv/bin/pip install --upgrade pip
./.venv/bin/pip install -r requirements.txt -r requirements-dev.txt

if [ "${KARTOFFELBOX:-0}" = "1" ]; then
  # --no-deps on purpose: chatterbox-tts pins torch==2.6.0 and transformers==5.2.0 exactly and
  # pulls gradio. See the header of requirements-kartoffelbox.txt.
  ./.venv/bin/pip install --no-deps chatterbox-tts==0.1.7
  ./.venv/bin/pip install -r requirements-kartoffelbox.txt
fi

# Kokoro ONNX weights (English).
mkdir -p models
BASE="https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0"
[ -f models/kokoro-v1.0.onnx ] || curl -L -o models/kokoro-v1.0.onnx "$BASE/kokoro-v1.0.onnx"
[ -f models/voices-v1.0.bin ]  || curl -L -o models/voices-v1.0.bin  "$BASE/voices-v1.0.bin"

cat <<'EOF'

OK.

German (TTS_ENGINE_DE, default `orpheus`) weights download on the first /v1/tts?lang=de call:
  - orpheus       SebastianBodza/Kartoffel_Orpheus-3B_german_natural-v0.1   ~6 GB, HF-gated
                  export HF_TOKEN and accept the licence, or set
                  ORPHEUS_REPO_DE=Thorsten-Voice/tv-orpheus-v1 (ungated, Apache-2.0).
                  For the production path run Orpheus on vLLM and set
                  ORPHEUS_BACKEND=vllm ORPHEUS_LLM_ENDPOINT=http://<vllm-host>:8000
  - kartoffelbox  SebastianBodza/Kartoffelbox-v0.1 + ResembleAI/chatterbox  ~3.2 GB, HF-gated
                  the better German voice; needs KARTOFFELBOX=1 ./setup.sh. Five synthetic
                  reference clips ship in voices/kartoffelbox/, so `voice` works out of the box;
                  a deployment's own clips go into models/kartoffelbox-voices/.

Then:  ./run.sh                              # serves on :9100
       .venv/bin/python -m pytest tests -q   # engines mocked, no GPU needed
EOF
