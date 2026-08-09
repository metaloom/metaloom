#!/usr/bin/env bash
#
# Stop the embeddings sidecar started by run.sh.
#
set -euo pipefail
cd "$(dirname "$0")"

export LLAMACPP_NAME="${LLAMACPP_EMBED_NAME:-loom-llamacpp-embeddings}"
exec ../llamacpp/stop.sh
