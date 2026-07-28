#!/usr/bin/env bash
# Create the venv for the Mage-Flow sidecar.
#
# Two-step install, in this order and for this reason:
#   1. requirements.txt  - upstream's pinned, tested version set.
#   2. mage-flow --no-deps - the model code from github.com/microsoft/Mage. It is not on
#      PyPI, and its own metadata declares gradio plus loose torch/transformers bounds;
#      installing it with deps would drag in a web UI we do not use and could resolve
#      torch/transformers *past* the versions the model code was tested against.
#
# flash-attn is deliberately NOT installed here. Mage-Flow prefers it but ships an SDPA
# fallback, and building it needs an nvcc whose MAJOR version matches the torch wheel's
# CUDA - a mismatch that is common and fails late. See README ("Attention backend").
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
# Pinned upstream commit rather than a moving branch: the checkpoints on the Hub are
# loaded by *this* code, and a silent main-branch drift would show up as a wrong image
# rather than as an error. Override to track main.
MAGEFLOW_GIT_REF="${MAGEFLOW_GIT_REF:-acf55fab9a6c3ef215ec0f52ca49112a99036959}"

if [ ! -d .venv ]; then
  "$PYTHON" -m venv .venv
fi
./.venv/bin/pip install --upgrade pip
./.venv/bin/pip install -r requirements.txt
./.venv/bin/pip install --no-deps \
  "mage-flow @ git+https://github.com/microsoft/Mage.git@${MAGEFLOW_GIT_REF}#subdirectory=mage_flow"

cat <<'EOF'

OK.

The checkpoint downloads lazily on the first /generate call (~17.5 GB: 8.2 GB DiT +
8.9 GB Qwen3-VL text encoder + 0.35 GB Mage-VAE). To pull it now instead:

  ./.venv/bin/python -c 'from huggingface_hub import snapshot_download as d; d("microsoft/Mage-Flow-Turbo")'

Weights are MIT-licensed - unusually, that covers commercial use, unlike the
non-commercial licences on SDXL / Ideogram 4 that the ideogram-sidecar carries.

Then:  ./run.sh          # serves on :9210
EOF
