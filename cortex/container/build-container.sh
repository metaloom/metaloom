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

# Where to find a built OpenCV tree. No Debian release packages the sonames the
# bundled libopencv_ffm.so needs, so the libraries come from a local OpenCV build.
#
# Which version is NOT hardcoded here. The shaded jar decides it: whichever
# opencv-ffm the build resolved (via video4j) carries a libopencv_ffm.so linked
# against one specific soname, and staging anything else produces an image that
# looks fine and then dies at runtime with
#   UnsatisfiedLinkError: libopencv_video.so.<N>: cannot open shared object file
# which is exactly what shipped when this script assumed 5.1 while the jar had
# been rebuilt against 4.10. So: read the sonames out of the jar, then stage a tree for
# each of them.
OPENCV_LIB_DIR="${OPENCV_LIB_DIR:-}"

# Print every OpenCV soname version the jar's bundled natives require, one per line.
#
# There is more than one. The shaded jar carries natives built against different OpenCV
# ABIs - opencv-ffm/yolo want .410 while inspireface (facedetect) wants .501 - and they
# coexist happily because the soname is part of the filename. Staging only one version is
# what breaks the other node at runtime, so collect them all.
required_opencv_sonames() {
    local jar="$1"
    local tmp reader=""
    tmp="$(mktemp -d)"
    unzip -o -q "$jar" '*.so' -d "$tmp" 2>/dev/null || true

    command -v objdump >/dev/null 2>&1 && reader="objdump -p"
    [[ -z "$reader" ]] && command -v readelf >/dev/null 2>&1 && reader="readelf -d"
    if [[ -z "$reader" ]]; then
        rm -rf "$tmp"
        echo "ERROR: need objdump or readelf (binutils) to read the jar's native deps." >&2
        exit 1
    fi

    find "$tmp" -name '*.so' -print0 \
        | xargs -0 -r -n1 $reader 2>/dev/null \
        | grep -o 'libopencv_core\.so\.[0-9]\+' \
        | sed 's/.*\.so\.//' \
        | sort -u
    rm -rf "$tmp"
}

# Copy the OpenCV libraries into the build context.
#
# Docker cannot ADD from outside the context and the OpenCV build lives in a
# sibling checkout, so they are staged under target/ - not committed, and rebuilt
# from whatever OpenCV tree matches the jar.
stage_opencv() {
    local jar="$1"
    local staged="$SCRIPT_DIR/target/opencv-libs"
    local wanted
    wanted="$(required_opencv_sonames "$jar")"
    if [[ -z "$wanted" ]]; then
        echo "ERROR: could not read any OpenCV soname from $jar" >&2
        exit 1
    fi
    echo "Shaded jar needs OpenCV soname(s): $(echo $wanted | tr '\n' ' ')"

    local search=()
    [[ -n "$OPENCV_LIB_DIR" ]] && search+=("$OPENCV_LIB_DIR")
    search+=("$REPO_ROOT/../opencv/build/lib")
    for d in "$REPO_ROOT"/../opencv-*/build/lib; do
        [[ -d "$d" ]] && search+=("$d")
    done

    rm -rf "$staged"
    mkdir -p "$staged"

    local staged_any=0
    local missing=()
    for want in $wanted; do
        local dir=""
        for c in "${search[@]}"; do
            if [[ -d "$c" ]] && compgen -G "$c/libopencv_core.so.$want" >/dev/null; then
                dir="$c"
                break
            fi
        done
        if [[ -z "$dir" ]]; then
            # Not fatal: the javacpp presets (libjniopencv_*) ship their own OpenCV and
            # extract it at runtime, so their soname needs no tree of ours.
            missing+=("$want")
            continue
        fi
        echo "  .so.$want <- $dir"
        # -a preserves the soname symlinks; resolving them would triple the image size.
        # The unversioned libopencv_*.so links collide between versions but are link-time
        # only, so whichever tree lands last wins and nothing at runtime depends on it.
        cp -a "$dir"/libopencv_*.so* "$staged/"
        staged_any=1
    done

    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "  note: no local OpenCV build for soname(s): ${missing[*]} (assumed self-contained)"
    fi
    if [[ $staged_any -eq 0 ]]; then
        echo "ERROR: none of the required OpenCV versions were found in:" >&2
        printf '  %s\n' "${search[@]}" >&2
        exit 1
    fi
}

build_server() {
    local jar="$SCRIPT_DIR/target/cortex-cli/cortex-cli.jar"
    if [[ ! -f "$jar" ]]; then
        echo "ERROR: $jar not found. Run 'mvn package' first." >&2
        exit 1
    fi
    stage_opencv "$jar"
    echo "Building metaloom/cortex-server:$TAG ..."
    docker build \
        -f "$SCRIPT_DIR/Containerfile" \
        -t "metaloom/cortex-server:$TAG" \
        "$REPO_ROOT"
}

target="${1:-all}"

build_server
echo "Done."
