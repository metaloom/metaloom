#!/bin/bash
#
# Starts the llama.cpp server used by the Cortex LLM node tests.
#
# The server speaks the OpenAI-compatible protocol, so the tests drive it through
# LLMProviderType.VLLM rather than the Ollama provider. See README.md.
#
# Everything below can be overridden from the environment, e.g.
#   PORT=9999 GPU=1 ./start.sh
#
set -euo pipefail

NAME=${NAME:-loom-test-llamacpp}
IMAGE=${IMAGE:-ghcr.io/ggml-org/llama.cpp}
VERSION=${VERSION:-server-cuda}

# Which GPU(s) the container may use. "all" hands over every visible device;
# a bare index ("0", "1") pins it to one card.
GPU=${GPU:-all}

# Deliberately not 8888 — that is where a manually started llama.cpp usually sits, and
# the test server has to be able to run alongside it.
PORT=${PORT:-8899}

# A small instruct model with first-class tool-calling support in llama.cpp.
# Qwen3 ships a chat template that declares tools natively, so --jinja gives real
# tool calls rather than the generic JSON-in-prompt fallback.
MODEL=${MODEL:-ggml-org/Qwen3-4B-GGUF:Q4_K_M}

# Model + HuggingFace caches live outside the workspace so they survive a clean.
CACHE=${CACHE:-/extra/cache}

# How long to wait for the server to report healthy. The first start downloads
# the model (a couple of GB), so this needs to be generous.
STARTUP_TIMEOUT=${STARTUP_TIMEOUT:-900}

mkdir -p "${CACHE}/llamacpp" "${CACHE}/huggingface"

docker rm -f "$NAME" >/dev/null 2>&1 || true

echo "Starting $NAME ($IMAGE:$VERSION) on port $PORT with model $MODEL"

docker run -d --device "nvidia.com/gpu=$GPU" --shm-size 1g \
	-p "$PORT:8080" \
	-v "${CACHE}/llamacpp:/models" \
	-v "${CACHE}/huggingface:/root/.cache/huggingface" \
	-e LLAMA_CACHE=/models \
	--name "$NAME" \
	"$IMAGE:$VERSION" --port 8080 --host 0.0.0.0 \
	--jinja \
	--reasoning-format auto \
	--ctx-size "${CTX_SIZE:-8192}" \
	-hf "$MODEL" >/dev/null

# Wait for readiness instead of tailing the log, so callers (and CI) can proceed
# once the model is actually loaded. /health reports 503 until then.
echo -n "Waiting for the server to become healthy "
deadline=$((SECONDS + STARTUP_TIMEOUT))
while true; do
	if ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
		echo ""
		echo "Container $NAME exited during startup. Last log lines:" >&2
		docker logs --tail 50 "$NAME" >&2 || true
		exit 1
	fi
	if curl -sf "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
		echo ""
		echo "Ready: http://127.0.0.1:$PORT (OpenAI API at /v1)"
		exit 0
	fi
	if [ "$SECONDS" -ge "$deadline" ]; then
		echo ""
		echo "Timed out after ${STARTUP_TIMEOUT}s. Last log lines:" >&2
		docker logs --tail 50 "$NAME" >&2 || true
		exit 1
	fi
	echo -n "."
	sleep 2
done
