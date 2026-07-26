#!/usr/bin/env bash
#
# Build the minimal Python Cortex worker image (metaloom/cortex-python).
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TAG="${TAG:-latest}"

echo "Building metaloom/cortex-python:$TAG ..."
docker build -f "$SCRIPT_DIR/Containerfile" -t "metaloom/cortex-python:$TAG" "$SCRIPT_DIR"
echo "Done."
