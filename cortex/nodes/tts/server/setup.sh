#!/usr/bin/env bash
# Create the venv and fetch the Kokoro weights. The Orpheus/SNAC weights are
# pulled from the Hugging Face Hub on first request.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"

if [ ! -d .venv ]; then
  "$PYTHON" -m venv .venv
fi
./.venv/bin/pip install --upgrade pip
./.venv/bin/pip install -r requirements.txt

# Kokoro ONNX weights (English).
mkdir -p models
BASE="https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0"
[ -f models/kokoro-v1.0.onnx ] || curl -L -o models/kokoro-v1.0.onnx "$BASE/kokoro-v1.0.onnx"
[ -f models/voices-v1.0.bin ]  || curl -L -o models/voices-v1.0.bin  "$BASE/voices-v1.0.bin"

cat <<'EOF'

OK. German (Orpheus/Kartoffel) weights download on the first /v1/tts?lang=de call.
  - Kartoffel is HF-gated: export HF_TOKEN and accept the repo licence, or set
    ORPHEUS_REPO_DE=Thorsten-Voice/tv-orpheus-v1 (ungated, Apache-2.0).
  - For the production path, run Orpheus on vLLM and set
    ORPHEUS_BACKEND=vllm ORPHEUS_LLM_ENDPOINT=http://<vllm-host>:8000
EOF
