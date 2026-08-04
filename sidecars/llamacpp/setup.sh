#!/usr/bin/env bash
#
# Pre-pull the llama.cpp server image. There is no venv to create - this sidecar is the
# official image, not Python sources - so this only exists so that the first ./run.sh does
# not sit on a multi-GB image pull with no output.
#
# The *model* is not pulled here: llama.cpp downloads it on first start via -hf, into
# ${LLAMACPP_CACHE}/llamacpp.
#
set -euo pipefail

IMAGE="${LLAMACPP_IMAGE:-ghcr.io/ggml-org/llama.cpp}"
VERSION="${LLAMACPP_VERSION:-server-cuda}"

RUNTIME="${LLAMACPP_RUNTIME:-}"
if [ -z "$RUNTIME" ]; then
	for candidate in docker podman; do
		if command -v "$candidate" >/dev/null 2>&1; then
			RUNTIME="$candidate"
			break
		fi
	done
fi
if [ -z "$RUNTIME" ]; then
	echo "Neither docker nor podman found in PATH. Install one, or set LLAMACPP_RUNTIME." >&2
	exit 1
fi

echo "Pulling $IMAGE:$VERSION via $RUNTIME"
"$RUNTIME" pull "$IMAGE:$VERSION"

cat <<'EOF'

OK. Start it with ./run.sh - the model downloads lazily on the first start:
  ggml-org/Qwen3-4B-GGUF:Q4_K_M   (~2.5 GB, Apache-2.0)

llama.cpp itself is MIT. The *weights* carry their own licence - check the model card
before shipping a different LLAMACPP_MODEL in a commercial deployment.
EOF
