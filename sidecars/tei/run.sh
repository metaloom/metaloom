#!/usr/bin/env bash
#
# Start the TEI sidecar - Hugging Face's text-embeddings-inference, serving the model Loom's
# semantic search (and therefore transcript search) embeds text with.
#
# Like ../llamacpp this is a container and nothing else: TEI ships an official image, so there is
# no venv and no server.py here. It runs under docker or podman.
#
#   ./run.sh              start it on :8091 and wait until it answers a real embedding
#   TEI_MODEL=... ./run.sh
#   ./stop.sh
#
set -euo pipefail
cd "$(dirname "$0")"

NAME="${TEI_NAME:-loom-tei}"

# 8091, next to the llama.cpp embeddings sidecar on 8090 rather than replacing it: the two serve
# the same OpenAI route with different models, and having both up is how you compare them before
# committing to one. Outside the 9100-9220 block, which belongs to the Python sidecars.
HOST="${TEI_HOST:-0.0.0.0}"
PORT="${TEI_PORT:-8091}"

# BGE-M3: multilingual, 1024 dimensions, 8192-token context. The long context is what makes it a
# good fit for transcript search — a minute of dialogue is a few hundred tokens and never has to
# be truncated, and the multilingual head matters because the transcripts are whatever language
# the audio was in rather than whatever language the catalogue is in.
#
# 🔴 If you change the model, change LOOM_SEARCH_EMBED_DIMENSIONS to match its output size in the
# same change. Loom rejects a reply of the wrong length rather than storing it: mixing vector
# lengths in one index segment produces distances that are numbers with no meaning.
#
# Lighter alternatives, both TEI-supported:
#   BAAI/bge-small-en-v1.5   384 dims,  ~130 MB, English only — fine for a smoke test
#   BAAI/bge-base-en-v1.5    768 dims,  ~440 MB, English only
MODEL="${TEI_MODEL:-BAAI/bge-m3}"
DIMENSIONS="${TEI_DIMENSIONS:-1024}"

# CPU by default, deliberately. This box has one GPU and the transcription and face-detection
# nodes want all of it; embedding 22 episodes of dialogue is minutes of CPU work done once, and a
# query embedding is a few milliseconds. Set TEI_GPU=all with TEI_IMAGE_TAG=latest (the CUDA
# image) when there is a card to spare.
GPU="${TEI_GPU:-none}"
IMAGE="${TEI_IMAGE:-ghcr.io/huggingface/text-embeddings-inference}"
# "cpu-latest" is the CPU build; "latest" is CUDA (Turing and newer have their own tags).
IMAGE_TAG="${TEI_IMAGE_TAG:-cpu-latest}"

# Weights outside the workspace so they survive a clean, and shared with the other sidecars: TEI
# and llama.cpp both read the Hugging Face cache, so a model pulled once is pulled once.
CACHE="${TEI_CACHE:-}"
if [ -z "$CACHE" ]; then
	CACHE=/extra/cache
	[ -d "$CACHE" ] || CACHE="$HOME/.cache"
fi

# The first start downloads the model.
STARTUP_TIMEOUT="${TEI_STARTUP_TIMEOUT:-900}"

# ---------------------------------------------------------------------------
# Container runtime
# ---------------------------------------------------------------------------
RUNTIME="${TEI_RUNTIME:-}"
if [ -z "$RUNTIME" ]; then
	for candidate in docker podman; do
		if command -v "$candidate" >/dev/null 2>&1; then
			RUNTIME="$candidate"
			break
		fi
	done
fi
if [ -z "$RUNTIME" ]; then
	echo "Neither docker nor podman found in PATH. Install one, or set TEI_RUNTIME." >&2
	exit 1
fi
if ! command -v "$RUNTIME" >/dev/null 2>&1; then
	echo "TEI_RUNTIME=$RUNTIME is not in PATH." >&2
	exit 1
fi

# GPU flags, same shape as ../llamacpp/run.sh: podman goes through CDI, docker's portable flag is
# --gpus. Override the pair wholesale with TEI_GPU_ARGS.
GPU_ARGS=()
if [ -n "${TEI_GPU_ARGS+x}" ]; then
	read -r -a GPU_ARGS <<<"$TEI_GPU_ARGS"
elif [ "$GPU" != "none" ] && [ -n "$GPU" ]; then
	if [ "$RUNTIME" = "podman" ]; then
		GPU_ARGS=(--device "nvidia.com/gpu=$GPU")
	elif [ "$GPU" = "all" ]; then
		GPU_ARGS=(--gpus all)
	else
		GPU_ARGS=(--gpus "device=$GPU")
	fi
	if [ "$IMAGE_TAG" = "cpu-latest" ]; then
		echo "Note: TEI_GPU=$GPU with the CPU image. Set TEI_IMAGE_TAG=latest for the CUDA build." >&2
	fi
fi

mkdir -p "${CACHE}/huggingface"

"$RUNTIME" rm -f "$NAME" >/dev/null 2>&1 || true

echo "Starting $NAME via $RUNTIME ($IMAGE:$IMAGE_TAG) on ${HOST}:${PORT} with model $MODEL"

"$RUNTIME" run -d \
	"${GPU_ARGS[@]}" \
	--shm-size 1g \
	-p "${HOST}:${PORT}:80" \
	-v "${CACHE}/huggingface:/data" \
	-e HF_HOME=/data \
	--name "$NAME" \
	"$IMAGE:$IMAGE_TAG" \
	--model-id "$MODEL" \
	--auto-truncate \
	${TEI_EXTRA_ARGS:-} >/dev/null

PROBE_HOST="$HOST"
[ "$PROBE_HOST" = "0.0.0.0" ] && PROBE_HOST=127.0.0.1

if ! command -v curl >/dev/null 2>&1; then
	echo "curl not found - not waiting for readiness. Follow: $RUNTIME logs -f $NAME"
	exit 0
fi

# Probe with a real embedding call, not with /health.
#
# This is the lesson from the llama.cpp embeddings sidecar, and it is worth restating: a server
# that is up but has no embedding model loaded answers /health perfectly well and then fails every
# actual request. Loom itself probes this way at boot for the same reason. The check here is
# stricter still — it asserts the reply is the length the configuration claims, because a wrong
# LOOM_SEARCH_EMBED_DIMENSIONS is silent until the vectors are already in the index.
echo -n "Waiting for the server to answer an embedding "
deadline=$((SECONDS + STARTUP_TIMEOUT))
while true; do
	if ! "$RUNTIME" ps --format '{{.Names}}' | grep -qx "$NAME"; then
		echo ""
		echo "Container $NAME exited during startup. Last log lines:" >&2
		"$RUNTIME" logs --tail 50 "$NAME" >&2 || true
		exit 1
	fi
	body="$(curl -sf -X POST "http://${PROBE_HOST}:${PORT}/v1/embeddings" \
		-H 'Content-Type: application/json' \
		-d "{\"input\":\"chevron seven locked\",\"model\":\"${MODEL}\"}" 2>/dev/null || true)"
	if [ -n "$body" ]; then
		echo ""
		# One grep rather than a JSON parser: jq is not guaranteed on these boxes.
		got="$(printf '%s' "$body" | tr ',' '\n' | grep -c '^\s*-\?[0-9]' || true)"
		if [ "$got" -ne "$DIMENSIONS" ]; then
			echo "⚠ The model returned ${got} numbers but TEI_DIMENSIONS says ${DIMENSIONS}." >&2
			echo "  Set LOOM_SEARCH_EMBED_DIMENSIONS=${got} or Loom will reject every vector." >&2
		fi
		cat <<EOF
Ready: http://${PROBE_HOST}:${PORT}/v1/embeddings  (model ${MODEL}, ${got} dimensions)

Turn semantic and transcript search on with:
  LOOM_SEARCH_SEMANTIC_ENABLED=true
  LOOM_SEARCH_EMBED_URL=http://${PROBE_HOST}:${PORT}/v1
  LOOM_SEARCH_EMBED_MODEL=${MODEL}
  LOOM_SEARCH_EMBED_DIMENSIONS=${got}
  LOOM_VECTOR_INDEX_PROVIDER=lucene
EOF
		exit 0
	fi
	if [ "$SECONDS" -ge "$deadline" ]; then
		echo ""
		echo "Timed out after ${STARTUP_TIMEOUT}s. Last log lines:" >&2
		"$RUNTIME" logs --tail 50 "$NAME" >&2 || true
		exit 1
	fi
	echo -n "."
	sleep 3
done
