#!/bin/bash

set -euo pipefail

# Starts the build container image

NAME=loom
NETWORK=dev

docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK"
docker rm -f "$NAME" >/dev/null 2>&1 || true

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
    metaloom/loom-demo:latest

echo "Demo container started as $NAME (http://localhost:8092)"
