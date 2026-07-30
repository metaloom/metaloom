#!/usr/bin/env bash
# Create the venv for the LTX-2 video sidecar.
#
# torch is installed FIRST, matching your CUDA, because the requirements.txt torch entry
# is intentionally unpinned (a CUDA mismatch fails late and confusingly). Override
# TORCH_INDEX_URL for a different CUDA; leave it empty to take whatever pip resolves.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
TORCH_INDEX_URL="${TORCH_INDEX_URL:-https://download.pytorch.org/whl/cu128}"

if [ ! -d .venv ]; then
  "$PYTHON" -m venv .venv
fi
./.venv/bin/pip install --upgrade pip

# torch first, matched to CUDA (see comment above).
if [ -n "$TORCH_INDEX_URL" ]; then
  ./.venv/bin/pip install torch --index-url "$TORCH_INDEX_URL"
fi
./.venv/bin/pip install -r requirements.txt

cat <<'EOF'

OK.

The LTX-2 repo downloads lazily on the first /generate call. It is BIG: the 27B Gemma3
text encoder alone is ~94 GB and the transformer ~36 GB. To pull it now instead:

  ./.venv/bin/python -c 'from huggingface_hub import snapshot_download as d; d("Lightricks/LTX-2")'

By default the sidecar quantizes both heavy components to nf4 4-bit, which fits a 24 GB
card (verified ~11 GB peak VRAM on an RTX 4090). For a >=48 GB / multi-GPU host you can
run bf16 with LTX2_QUANTIZE=none.

Weights are under the LTX-2 Community License — read it before any commercial use.

Then:  ./run.sh          # serves on :9220
EOF
