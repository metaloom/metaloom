#!/usr/bin/env bash
#
# Build the custom Cortex worker image (metaloom/cortex-custom).
#
# Prerequisites:
#   * The metaloom/cortex-server base image exists locally (cortex/container/build-container.sh).
#   * This module has been packaged: mvn -pl examples/cortex-custom -am package
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TAG="${TAG:-latest}"

JAR="$SCRIPT_DIR/target/cortex-custom.jar"
if [[ ! -f "$JAR" ]]; then
    echo "ERROR: $JAR not found. Run 'mvn -pl examples/cortex-custom -am package' first." >&2
    exit 1
fi

echo "Building metaloom/cortex-custom:$TAG ..."
docker build -f "$SCRIPT_DIR/Containerfile" -t "metaloom/cortex-custom:$TAG" "$SCRIPT_DIR"
echo "Done."
