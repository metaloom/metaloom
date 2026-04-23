#!/bin/bash

set -euo pipefail

# Starts the loom-demo container image.
#
# Usage:
#   ./start-demo.sh           # JVM image (metaloom/loom-demo:latest)
#   ./start-demo.sh native    # Native image (metaloom/loom-demo:latest-native)
#
# Environment variables:
#   TAG  Image tag (default: latest). Native variant gets "-native" appended.

NAME=loom
NETWORK=dev
TAG="${TAG:-latest}"
VARIANT="${1:-jvm}"

case "$VARIANT" in
    jvm)    IMAGE="metaloom/loom-demo:${TAG}" ;;
    native) IMAGE="metaloom/loom-demo:${TAG}-native" ;;
    *)      echo "Usage: $0 [jvm|native]" >&2; exit 1 ;;
esac

docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK"
docker rm -f "$NAME" >/dev/null 2>&1 || true

echo "Starting $IMAGE as $NAME ..."
docker run -d \
    --name "$NAME" \
    --network "$NETWORK" \
    -p 8092:8092 \
    -e LOOM_INITIAL_PASSWORD=finger \
    -e LOOM_DB_USERNAME=postgres \
    -e LOOM_DB_PASSWORD=finger \
    -e LOOM_DB_NAME=loom \
    -e LOOM_DB_HOST=postgres-demo \
    -e LOOM_DB_PORT=5432 \
    "$IMAGE"

echo "Demo container started as $NAME (http://localhost:8092)"
