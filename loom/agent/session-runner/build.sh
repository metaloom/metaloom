#!/usr/bin/env bash
# Build the Loom Session Runner image. Uses podman if available, otherwise docker.
#
# The image reference must match LOOM_AGENT_SANDBOX_IMAGE (default metaloom/loom-session-runner:latest).
set -euo pipefail

cd "$(dirname "$0")"

IMAGE="${LOOM_AGENT_SANDBOX_IMAGE:-metaloom/loom-session-runner:latest}"
ENGINE="${CONTAINER_ENGINE:-}"
if [ -z "$ENGINE" ]; then
  if command -v podman >/dev/null 2>&1; then ENGINE=podman; else ENGINE=docker; fi
fi

echo "Building ${IMAGE} with ${ENGINE}"
"${ENGINE}" build -f Containerfile -t "${IMAGE}" .
echo "Built ${IMAGE}"
