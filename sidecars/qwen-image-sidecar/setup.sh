#!/usr/bin/env bash
# Create the venv for the Qwen-Image-2.1 sidecar.
#
# torch and torchvision are installed FIRST, matching your CUDA, because the
# requirements.txt entries are intentionally absent (a CUDA mismatch fails late and
# confusingly). Override TORCH_INDEX_URL for a different CUDA; leave it empty to take
# whatever pip resolves.
#
# torchvision is NOT optional here: the Qwen3-VL processor pulls in
# Qwen3VLVideoProcessor, which hard-requires it and fails the model load without it,
# even though this sidecar never touches video.
#
# diffusers comes from GIT, not PyPI: QwenImage21Pipeline was merged on 2026-09-18 and
# is in no release up to 0.40.0 (2026-08-20). The ref is pinned rather than tracking
# main so a rebuild months from now still yields the pipeline this server was written
# against. Keep it in step with qwen_loader.DIFFUSERS_GIT_REF.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
TORCH_INDEX_URL="${TORCH_INDEX_URL:-https://download.pytorch.org/whl/cu128}"
DIFFUSERS_GIT_REF="${DIFFUSERS_GIT_REF:-6256aa7666cedd47443adc8f82da9a10e110b09c}"

if [ ! -d .venv ]; then
  "$PYTHON" -m venv .venv
fi
./.venv/bin/pip install --upgrade pip

# torch + torchvision first, matched to CUDA (see comment above).
if [ -n "$TORCH_INDEX_URL" ]; then
  ./.venv/bin/pip install torch torchvision --index-url "$TORCH_INDEX_URL"
fi
./.venv/bin/pip install -r requirements.txt
./.venv/bin/pip install "diffusers @ git+https://github.com/huggingface/diffusers@${DIFFUSERS_GIT_REF}"

cat <<'EOF'

OK.

The checkpoint downloads lazily on the first request. It is 33 GB in bf16:
  text encoder (Qwen3-VL)  17.5 GB
  transformer (7B DiT)     14.2 GB
  VAE                       1.35 GB

To pull it now instead of paying for it on the first call:

  ./.venv/bin/python -c 'from huggingface_hub import snapshot_download as d; d("Qwen/Qwen-Image-2.1")'

The weights are not the figure that matters. Measured on an H200: ~46 GB resident at
the default output_resolution of 1024, ~66 GB at 2048. Activations and the KV cache are
not returned between requests, so a card sized for the weights alone OOMs on the first
generation. 48 GB is the floor at 1K, 80 GB at 2K.

On anything smaller set QWENIMAGE_OFFLOAD=1, which swaps components CPU<->GPU per
stage at a real latency cost.

LICENCE: Qwen-Image-2.1 is under the Qwen RESEARCH LICENSE - NON-COMMERCIAL USE ONLY
("research or evaluation purposes"). Read it before any deployment that is not
research. Mage-Flow (sidecars/mage-flow-sidecar, MIT) remains the documented default
for shipping deployments.

Then:  ./run.sh          # serves on :9230
       ./qwen_smoke.py health --endpoint http://localhost:9230
EOF
