#!/usr/bin/env bash
#
# Start the llama.cpp sidecar - the OpenAI-compatible backend the `llm` node talks to.
#
# Unlike the other sidecars in this folder there is no venv and no server.py: llama.cpp
# ships an official server image, so this script is the whole sidecar. It runs under
# either docker or podman (see LLAMACPP_RUNTIME below).
#
# Everything is overridable from the environment:
#   LLAMACPP_PORT=9000 LLAMACPP_GPU=1 ./run.sh
#
set -euo pipefail
cd "$(dirname "$0")"

NAME="${LLAMACPP_NAME:-loom-llamacpp}"
IMAGE="${LLAMACPP_IMAGE:-ghcr.io/ggml-org/llama.cpp}"
VERSION="${LLAMACPP_VERSION:-server-cuda}"

# 8080 is AbstractLlmNodeOptions.DEFAULT_OPENAI_URL (http://127.0.0.1:8080/v1), so the
# node finds this sidecar without any option being set. It deliberately sits outside the
# 9100-9220 block the Python sidecars use - that block is ours, 8080 is llama.cpp's.
HOST="${LLAMACPP_HOST:-0.0.0.0}"
PORT="${LLAMACPP_PORT:-8080}"

# A small instruct model with first-class tool-calling support in llama.cpp. Same model
# as loom-test-env/llamacpp, so the two share one download.
MODEL="${LLAMACPP_MODEL:-ggml-org/Qwen3-4B-GGUF:Q4_K_M}"
CTX_SIZE="${LLAMACPP_CTX_SIZE:-8192}"

# Which GPU(s) the container may use: "all", a bare index ("0"), or "none" for CPU-only.
GPU="${LLAMACPP_GPU:-all}"

# Weights live outside the workspace so they survive a clean. /extra/cache is where the
# dev boxes keep them (and what loom-test-env/llamacpp uses), so prefer it when present -
# that way the two containers share one download instead of pulling the model twice.
CACHE="${LLAMACPP_CACHE:-}"
if [ -z "$CACHE" ]; then
	CACHE=/extra/cache
	[ -d "$CACHE" ] || CACHE="$HOME/.cache"
fi

# The first start downloads the model, so this has to be generous.
STARTUP_TIMEOUT="${LLAMACPP_STARTUP_TIMEOUT:-900}"

# ---------------------------------------------------------------------------
# Container runtime
# ---------------------------------------------------------------------------
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
if ! command -v "$RUNTIME" >/dev/null 2>&1; then
	echo "LLAMACPP_RUNTIME=$RUNTIME is not in PATH." >&2
	exit 1
fi

# ---------------------------------------------------------------------------
# GPU flags
#
# podman passes GPUs through CDI; docker's portable flag is --gpus (it also understands
# CDI from 25.0 on, but only when a CDI spec is actually installed - which is why that is
# not the default here). Override the pair wholesale with LLAMACPP_GPU_ARGS, e.g.
#   LLAMACPP_GPU_ARGS="--device nvidia.com/gpu=all" ./run.sh
# ---------------------------------------------------------------------------
GPU_ARGS=()
if [ -n "${LLAMACPP_GPU_ARGS+x}" ]; then
	read -r -a GPU_ARGS <<<"$LLAMACPP_GPU_ARGS"
elif [ "$GPU" != "none" ] && [ -n "$GPU" ]; then
	if [ "$RUNTIME" = "podman" ]; then
		GPU_ARGS=(--device "nvidia.com/gpu=$GPU")
	elif [ "$GPU" = "all" ]; then
		GPU_ARGS=(--gpus all)
	else
		GPU_ARGS=(--gpus "device=$GPU")
	fi
elif [ "$VERSION" = "server-cuda" ]; then
	echo "Note: LLAMACPP_GPU=none with the CUDA image. For CPU-only use LLAMACPP_VERSION=server." >&2
fi

mkdir -p "${CACHE}/llamacpp" "${CACHE}/huggingface"

"$RUNTIME" rm -f "$NAME" >/dev/null 2>&1 || true

echo "Starting $NAME via $RUNTIME ($IMAGE:$VERSION) on ${HOST}:${PORT} with model $MODEL"

# --jinja is load-bearing: without it llama.cpp ignores the model's chat template and
# falls back to a generic JSON-in-the-prompt scheme, so `tools` stops producing real
# tool_calls. --reasoning-format auto keeps thinking blocks out of the message content.
"$RUNTIME" run -d \
	"${GPU_ARGS[@]}" \
	--shm-size 1g \
	-p "${HOST}:${PORT}:8080" \
	-v "${CACHE}/llamacpp:/models" \
	-v "${CACHE}/huggingface:/root/.cache/huggingface" \
	-e LLAMA_CACHE=/models \
	--name "$NAME" \
	"$IMAGE:$VERSION" --port 8080 --host 0.0.0.0 \
	--jinja \
	--reasoning-format auto \
	--ctx-size "$CTX_SIZE" \
	-hf "$MODEL" \
	${LLAMACPP_EXTRA_ARGS:-} >/dev/null

# Wait for readiness rather than tailing the log, so callers can chain on this script.
# /health answers 503 until the weights are loaded.
PROBE_HOST="$HOST"
[ "$PROBE_HOST" = "0.0.0.0" ] && PROBE_HOST=127.0.0.1

if ! command -v curl >/dev/null 2>&1; then
	echo "curl not found - not waiting for readiness. Follow: $RUNTIME logs -f $NAME"
	exit 0
fi

echo -n "Waiting for the server to become healthy "
deadline=$((SECONDS + STARTUP_TIMEOUT))
while true; do
	if ! "$RUNTIME" ps --format '{{.Names}}' | grep -qx "$NAME"; then
		echo ""
		echo "Container $NAME exited during startup. Last log lines:" >&2
		"$RUNTIME" logs --tail 50 "$NAME" >&2 || true
		exit 1
	fi
	if curl -sf "http://${PROBE_HOST}:${PORT}/health" >/dev/null 2>&1; then
		echo ""
		echo "Ready: http://${PROBE_HOST}:${PORT} (OpenAI API at /v1)"
		echo "Point the llm node at it with: nodes.llm.openaiUrl=http://${PROBE_HOST}:${PORT}/v1"
		exit 0
	fi
	if [ "$SECONDS" -ge "$deadline" ]; then
		echo ""
		echo "Timed out after ${STARTUP_TIMEOUT}s. Last log lines:" >&2
		"$RUNTIME" logs --tail 50 "$NAME" >&2 || true
		exit 1
	fi
	echo -n "."
	sleep 2
done
