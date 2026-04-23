#!/usr/bin/env bash
#
# Build the Loom container images locally using Docker/Podman.
#
# Two image variants are supported:
#   * jvm    (default) - Eclipse Temurin JRE running the shaded jar
#   * native           - GraalVM/SubstrateVM ahead-of-time compiled binary
#
# Usage:
#   ./build-containers.sh                         # BOTH JVM and NATIVE: demo + server
#   ./build-containers.sh jvm                     # JVM: build demo + server
#   ./build-containers.sh jvm demo                # JVM: build only the demo image
#   ./build-containers.sh jvm server              # JVM: build only the server image
#   ./build-containers.sh native                  # NATIVE: build demo + server
#   ./build-containers.sh native demo             # NATIVE: build only the demo image
#   ./build-containers.sh native server           # NATIVE: build only the server image
#   ./build-containers.sh all                     # Same as no args (jvm + native, demo + server)
#
# Environment variables:
#   TAG          Image tag suffix (default: latest). Native images get a
#                "-native" suffix appended automatically (e.g. latest-native).
#   GRAALVM_HOME GraalVM home used to build the native image.
#                Defaults to /opt/jvm/graalvm-25.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
UI_BUILD="$REPO_ROOT/loom-ui/build"

TAG="${TAG:-latest}"
GRAALVM_HOME="${GRAALVM_HOME:-/opt/jvm/graalvm-25}"

require_ui() {
    if [[ ! -d "$UI_BUILD" ]]; then
        echo "ERROR: $UI_BUILD not found. Run 'npm run build' in loom-ui first." >&2
        exit 1
    fi
}

build_jvm_demo() {
    local jar="$SCRIPT_DIR/demo/target/loom-demo.jar"
    if [[ ! -f "$jar" ]]; then
        echo "ERROR: $jar not found. Run 'mvn package' first." >&2
        exit 1
    fi
    require_ui
    echo "Building metaloom/loom-demo:$TAG ..."
    docker build -f "$SCRIPT_DIR/demo/Containerfile" -t "metaloom/loom-demo:$TAG" "$REPO_ROOT"
}

build_jvm_server() {
    local jar="$SCRIPT_DIR/server/target/loom-server.jar"
    if [[ ! -f "$jar" ]]; then
        echo "ERROR: $jar not found. Run 'mvn package' first." >&2
        exit 1
    fi
    require_ui
    echo "Building metaloom/loom-server:$TAG ..."
    docker build -f "$SCRIPT_DIR/server/Containerfile" -t "metaloom/loom-server:$TAG" "$REPO_ROOT"
}

ensure_graalvm() {
    if [[ ! -x "$GRAALVM_HOME/bin/native-image" ]]; then
        echo "ERROR: native-image not found at $GRAALVM_HOME/bin/native-image" >&2
        echo "Set GRAALVM_HOME to a GraalVM installation that has native-image." >&2
        exit 1
    fi
}

maven_native_build() {
    local module="$1"
    local binary="$2"
    if [[ -x "$binary" ]]; then
        echo "Reusing existing native binary: $binary"
        return 0
    fi
    ensure_graalvm
    echo "Building native binary for loom/containers/$module via Maven (Profile: native) ..."
    JAVA_HOME="$GRAALVM_HOME" "mvn" -f "$REPO_ROOT/pom.xml" \
        -Pnative -DskipTests -pl "loom/containers/$module" -am package
    if [[ ! -x "$binary" ]]; then
        echo "ERROR: native build did not produce $binary" >&2
        exit 1
    fi
}

build_native_demo() {
    local binary="$SCRIPT_DIR/demo/target/loom-demo"
    maven_native_build demo "$binary"
    require_ui
    local image_tag="${TAG}-native"
    echo "Building metaloom/loom-demo:$image_tag (native) ..."
    docker build -f "$SCRIPT_DIR/demo/Containerfile.native" -t "metaloom/loom-demo:$image_tag" "$REPO_ROOT"
}

build_native_server() {
    local binary="$SCRIPT_DIR/server/target/loom-server"
    maven_native_build server "$binary"
    require_ui
    local image_tag="${TAG}-native"
    echo "Building metaloom/loom-server:$image_tag (native) ..."
    docker build -f "$SCRIPT_DIR/server/Containerfile.native" -t "metaloom/loom-server:$image_tag" "$REPO_ROOT"
}

variant="both"
case "${1:-}" in
    jvm)    variant="jvm";    shift ;;
    native) variant="native"; shift ;;
    both|all) variant="both"; shift ;;
esac

target="${1:-all}"

case "$variant:$target" in
    jvm:demo)      build_jvm_demo ;;
    jvm:server)    build_jvm_server ;;
    jvm:all)       build_jvm_demo;    build_jvm_server ;;
    native:demo)   build_native_demo ;;
    native:server) build_native_server ;;
    native:all)    build_native_demo; build_native_server ;;
    both:demo)     build_jvm_demo;    build_native_demo ;;
    both:server)   build_jvm_server;  build_native_server ;;
    both:all)      build_jvm_demo;    build_jvm_server; build_native_demo; build_native_server ;;
    *)
        echo "Usage: $0 [jvm|native|both] [demo|server|all]" >&2
        exit 1
        ;;
esac

echo "Done."
