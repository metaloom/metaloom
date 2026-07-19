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

# Where to find a built OpenCV 5.1 tree. The libopencv_ffm.so shipped inside the
# opencv-ffm jar is linked against soname .so.501, which no Debian release
# packages, so the libraries have to come from a local OpenCV build.
OPENCV_LIB_DIR="${OPENCV_LIB_DIR:-$REPO_ROOT/../opencv/build/lib}"

# Copy the OpenCV libraries into the build context.
#
# Docker cannot ADD from outside the context and the OpenCV build lives in a
# sibling checkout, so they are staged under target/ - not committed, and rebuilt
# from whatever OpenCV tree the developer points at.
stage_opencv() {
    local staged="$SCRIPT_DIR/target/opencv-libs"
    if [[ ! -d "$OPENCV_LIB_DIR" ]]; then
        echo "ERROR: OpenCV libraries not found at $OPENCV_LIB_DIR" >&2
        echo "Build OpenCV 5.1 first (see the opencv checkout's build.sh), or set" >&2
        echo "OPENCV_LIB_DIR to the directory holding libopencv_core.so.501 etc." >&2
        exit 1
    fi
    if ! compgen -G "$OPENCV_LIB_DIR/libopencv_core.so.501" >/dev/null; then
        echo "ERROR: $OPENCV_LIB_DIR exists but holds no libopencv_core.so.501." >&2
        echo "The bundled libopencv_ffm.so needs OpenCV 5.1; that tree is a different version." >&2
        exit 1
    fi
    echo "Staging OpenCV libraries from $OPENCV_LIB_DIR ..."
    rm -rf "$staged"
    mkdir -p "$staged"
    # -a preserves the soname symlinks; resolving them would triple the image size.
    cp -a "$OPENCV_LIB_DIR"/libopencv_*.so* "$staged/"
}

build_server() {
    local jar="$SCRIPT_DIR/target/cortex-cli/cortex-cli.jar"
    if [[ ! -f "$jar" ]]; then
        echo "ERROR: $jar not found. Run 'mvn package' first." >&2
        exit 1
    fi
    stage_opencv
    echo "Building metaloom/cortex-server:$TAG ..."
    docker build \
        -f "$SCRIPT_DIR/Containerfile" \
        -t "metaloom/cortex-server:$TAG" \
        "$REPO_ROOT"
}

target="${1:-all}"

build_server
echo "Done."
