#!/usr/bin/env bash
#
# Build the custom Cortex worker image (metaloom/cortex-custom).
#
# The image builds on top of metaloom/cortex-server and overlays only this
# example's thin jars — the stock worker classpath (all built-in nodes, the
# pipeline engine, the native runtime) is reused from the base image. So the
# added layer is a few tens of KB, not a fresh copy of every dependency.
#
# Prerequisites:
#   * The metaloom/cortex-server base image exists locally
#     (cortex/container/build-container.sh).
#
# It packages the example (thin jars, no shading), stages them, and builds.
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TAG="${TAG:-latest}"

echo "Packaging cortex-custom + cortex-custom-node ..."
mvn -f "$REPO_ROOT/pom.xml" -q -DskipTests \
    -pl examples/cortex-custom-node,examples/cortex-custom -am package

# Resolve the main (thin) jars, excluding the -sources / -javadoc artifacts.
pick_jar() {
    local dir="$1"
    find "$dir" -maxdepth 1 -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
        | sort | head -n1
}
CUSTOM_JAR="$(pick_jar "$SCRIPT_DIR/target")"
NODE_JAR="$(pick_jar "$REPO_ROOT/examples/cortex-custom-node/target")"

if [[ -z "$CUSTOM_JAR" || -z "$NODE_JAR" ]]; then
    echo "ERROR: could not locate the built thin jars. Did 'mvn package' succeed?" >&2
    exit 1
fi

STAGE="$SCRIPT_DIR/target/image"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$CUSTOM_JAR" "$STAGE/cortex-custom.jar"
cp "$NODE_JAR" "$STAGE/cortex-custom-node.jar"

echo "Building metaloom/cortex-custom:$TAG ..."
docker build -f "$SCRIPT_DIR/Containerfile" -t "metaloom/cortex-custom:$TAG" "$STAGE"
echo "Done."
