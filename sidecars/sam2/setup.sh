#!/usr/bin/env bash
# Create the venv. The facebook/sam2.1-* checkpoints are ungated and are pulled from
# the Hugging Face Hub on the first request for each model id - no HF_TOKEN needed and
# no checkpoints/download_ckpts.sh step (that is the facebookresearch/sam2 route, which
# this sidecar deliberately does not take).
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"

if [ ! -d .venv ]; then
  "$PYTHON" -m venv .venv
fi
./.venv/bin/pip install --upgrade pip

# torch first, from the index that matches your CUDA. Installing it as a transitive
# dependency of transformers picks whatever the default index serves, and a CUDA
# mismatch then fails at the first forward pass rather than here.
TORCH_INDEX_URL="${TORCH_INDEX_URL:-https://download.pytorch.org/whl/cu128}"
./.venv/bin/pip install --index-url "$TORCH_INDEX_URL" "torch>=2.5.1" "torchvision>=0.20.1"

./.venv/bin/pip install -r requirements.txt

# Which transformers minor first shipped the SAM 2 video classes is not pinned above
# on purpose - assert the imports resolve instead of guessing a version.
./.venv/bin/python -c \
  "from transformers import Sam2Model, Sam2VideoModel, Sam2Processor, Sam2VideoProcessor" \
  || { echo "ERROR: this transformers release has no SAM 2. Upgrade: ./.venv/bin/pip install -U transformers"; exit 1; }

cat <<'EOF'

OK. The checkpoint downloads lazily on the first request for each model id:
  facebook/sam2.1-hiera-tiny        38.9M params
  facebook/sam2.1-hiera-small       46.0M params   <- default
  facebook/sam2.1-hiera-base-plus   80.8M params
  facebook/sam2.1-hiera-large      224.4M params

SAM 2 code and the 2.1 checkpoints are Apache-2.0, so every member of this family is
usable commercially - unlike the Depth Anything V2 family, where only Small is.

To pre-warm the default instead of paying for it on the first request:
  ./.venv/bin/huggingface-cli download facebook/sam2.1-hiera-small
EOF
