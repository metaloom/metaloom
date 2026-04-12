#!/usr/bin/env bash
#
# Build the Loom container images locally using Podman.
#
# Usage:
#   ./build-containers.sh              # build both demo and server images
#   ./build-containers.sh demo         # build only the demo image
#   ./build-containers.sh server       # build only the server image
#
# Prerequisites:
#   The Maven project must have been built first:
#     mvn -f ../../pom.xml clean package -DskipTests -pl loom/containers/demo,loom/containers/server -am
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TAG="${TAG:-latest}"

REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
UI_BUILD="$REPO_ROOT/loom-ui/build"

build_demo() {
    local jar="$SCRIPT_DIR/demo/target/loom-demo.jar"
    if [[ ! -f "$jar" ]]; then
        echo "ERROR: $jar not found. Run 'mvn package' first." >&2
        exit 1
    fi
    if [[ ! -d "$UI_BUILD" ]]; then
        echo "ERROR: $UI_BUILD not found. Run 'npm run build' in loom-ui first." >&2
        exit 1
    fi
    echo "Building metaloom/loom-demo:$TAG ..."
    docker build \
        -f "$SCRIPT_DIR/demo/Containerfile" \
        -t "metaloom/loom-demo:$TAG" \
        "$REPO_ROOT"
}

build_server() {
    local jar="$SCRIPT_DIR/server/target/loom-server.jar"
    if [[ ! -f "$jar" ]]; then
        echo "ERROR: $jar not found. Run 'mvn package' first." >&2
        exit 1
    fi
    if [[ ! -d "$UI_BUILD" ]]; then
        echo "ERROR: $UI_BUILD not found. Run 'npm run build' in loom-ui first." >&2
        exit 1
    fi
    echo "Building metaloom/loom-server:$TAG ..."
    docker build \
        -f "$SCRIPT_DIR/server/Containerfile" \
        -t "metaloom/loom-server:$TAG" \
        "$REPO_ROOT"
}

target="${1:-all}"

case "$target" in
    demo)   build_demo ;;
    server) build_server ;;
    all)    build_demo; build_server ;;
    *)
        echo "Usage: $0 [demo|server|all]" >&2
        exit 1
        ;;
esac

echo "Done."
