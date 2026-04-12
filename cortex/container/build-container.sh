#!/usr/bin/env bash
#
# Build the Cortex container images locally using docker.
#
# Usage:
#   ./build-container.sh
# Prerequisites:
#   The Maven project must have been built first:
#     mvn -f ../../pom.xml clean package -DskipTests -pl cortex/container,cortex/cli -am
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TAG="${TAG:-latest}"

REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

build_server() {
    local jar="$SCRIPT_DIR/target/cortex-cli/cortex-cli.jar"
    if [[ ! -f "$jar" ]]; then
        echo "ERROR: $jar not found. Run 'mvn package' first." >&2
        exit 1
    fi
    echo "Building metaloom/cortex-server:$TAG ..."
    docker build \
        -f "$SCRIPT_DIR/Containerfile" \
        -t "metaloom/cortex-server:$TAG" \
        "$REPO_ROOT"
}

target="${1:-all}"

build_server
echo "Done."
