#!/usr/bin/env bash
#
# Start the embeddings sidecar - the host Loom's semantic search embeds text with.
#
# This is the same llama.cpp server image the `llm` node uses, run a second time with an
# embedding model and --embeddings. It is deliberately a wrapper around ../llamacpp/run.sh
# rather than a copy of it: container runtime detection, GPU flags, the weights cache and
# the readiness probe are all fiddly, already solved there, and must not drift.
#
# Everything the parent script understands still applies; these are the extra knobs:
#   LLAMACPP_EMBED_PORT=8090          the port to serve on (8080 is the chat sidecar's)
#   LLAMACPP_EMBED_MODEL=<hf repo>    the GGUF embedding model
#   LLAMACPP_EMBED_GPU=none           CPU is fine for a small embedding model
#   LLAMACPP_EMBED_POOLING=mean       llama.cpp pooling type
#
set -euo pipefail
cd "$(dirname "$0")"

PORT="${LLAMACPP_EMBED_PORT:-8090}"

# A small, well-known 768-dimension text embedder that runs comfortably on CPU. 768 is also
# LOOM_SEARCH_EMBED_DIMENSIONS's default, so the pair works with no configuration.
# 🔴 If you change the model, change LOOM_SEARCH_EMBED_DIMENSIONS to match its output size.
# Loom rejects a reply of the wrong length rather than storing it - a wrong dimension mixes
# incomparable vectors into one index segment, which is not recoverable by re-querying.
MODEL="${LLAMACPP_EMBED_MODEL:-nomic-ai/nomic-embed-text-v1.5-GGUF:Q8_0}"

export LLAMACPP_NAME="${LLAMACPP_EMBED_NAME:-loom-llamacpp-embeddings}"
export LLAMACPP_PORT="$PORT"
export LLAMACPP_MODEL="$MODEL"
export LLAMACPP_GPU="${LLAMACPP_EMBED_GPU:-none}"
# An embedding model has no chat template, so the CPU image is the right default; the parent
# script warns about GPU=none only for the CUDA one.
export LLAMACPP_VERSION="${LLAMACPP_EMBED_VERSION:-server}"
export LLAMACPP_EXTRA_ARGS="--embeddings --pooling ${LLAMACPP_EMBED_POOLING:-mean} ${LLAMACPP_EMBED_EXTRA_ARGS:-}"

../llamacpp/run.sh

PROBE_HOST="${LLAMACPP_HOST:-0.0.0.0}"
[ "$PROBE_HOST" = "0.0.0.0" ] && PROBE_HOST=127.0.0.1

cat <<EOF

Embeddings ready at http://${PROBE_HOST}:${PORT}/v1/embeddings
(The parent script's "point the llm node at it" line is about the chat sidecar - ignore it here.)

Turn semantic search on with:
  LOOM_SEARCH_SEMANTIC_ENABLED=true
  LOOM_SEARCH_EMBED_URL=http://${PROBE_HOST}:${PORT}/v1
  LOOM_SEARCH_EMBED_MODEL=${MODEL}
  LOOM_SEARCH_EMBED_DIMENSIONS=768
  LOOM_VECTOR_INDEX_PROVIDER=lucene
EOF
