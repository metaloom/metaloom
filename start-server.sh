#!/bin/bash

set -euo pipefail

# Starts the loom-server container image.
#
# Usage:
#   ./start-server.sh           # JVM image (metaloom/loom-server:latest)
#   ./start-server.sh native    # Native image (metaloom/loom-server:latest-native)
#
# Environment variables:
#   TAG  Image tag (default: latest). Native variant gets "-native" appended.

TAG="${TAG:-latest}"
VARIANT="${1:-jvm}"

case "$VARIANT" in
    jvm)    IMAGE="metaloom/loom-server:${TAG}" ;;
    native) IMAGE="metaloom/loom-server:${TAG}-native" ;;
    *)      echo "Usage: $0 [jvm|native]" >&2; exit 1 ;;
esac

echo "Starting $IMAGE ..."
docker run --network dev \
    -p 8092:8092 \
    -e LOOM_INITIAL_PASSWORD=finger \
    -e LOOM_DB_USERNAME=loom \
    -e LOOM_DB_PASSWORD=loom \
    "$IMAGE"
