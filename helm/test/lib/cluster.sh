# shellcheck shell=bash
#
# k3d cluster lifecycle and image plumbing.
#
# Sourced by run.sh — not executable on its own.

# ── Tool bootstrap ────────────────────────────────────────────────────
#
# kubectl and k3d are downloaded into test/.bin rather than expected on PATH, so
# the harness runs on a machine that has never seen Kubernetes. helm and docker
# are NOT bootstrapped: helm is what we are testing with, and a docker daemon
# cannot be installed from a script.

ensure_tools() {
    mkdir -p "$BIN_DIR"

    require_cmd docker "install Docker, or point DOCKER_HOST at a daemon"
    require_cmd helm   "https://helm.sh/docs/intro/install/"
    require_cmd curl
    require_cmd jq

    docker info >/dev/null 2>&1 || fatal "cannot talk to a Docker daemon (is it running, and are you in the docker group?)"

    if ! command -v kubectl >/dev/null 2>&1 && [[ ! -x "$BIN_DIR/kubectl" ]]; then
        info "downloading kubectl into $(_relpath "$BIN_DIR")"
        local ver
        ver="$(curl -fsSL --max-time 30 https://dl.k8s.io/release/stable.txt)" \
            || fatal "could not resolve the current kubectl version (no network?)"
        curl -fsSL --max-time 180 -o "$BIN_DIR/kubectl" \
            "https://dl.k8s.io/release/${ver}/bin/linux/amd64/kubectl" \
            || fatal "kubectl download failed"
        chmod +x "$BIN_DIR/kubectl"
    fi

    if ! command -v k3d >/dev/null 2>&1 && [[ ! -x "$BIN_DIR/k3d" ]]; then
        info "downloading k3d into $(_relpath "$BIN_DIR")"
        curl -fsSL --max-time 180 -o "$BIN_DIR/k3d" \
            "https://github.com/k3d-io/k3d/releases/latest/download/k3d-linux-amd64" \
            || fatal "k3d download failed"
        chmod +x "$BIN_DIR/k3d"
    fi

    export PATH="$BIN_DIR:$PATH"
    log "kubectl $(kubectl version --client -o json 2>/dev/null | jq -r .clientVersion.gitVersion)"
    log "k3d     $(k3d version 2>/dev/null | head -1 | awk '{print $3}')"
    log "helm    $(helm version --short 2>/dev/null)"
}

_relpath() { printf '%s' "${1#"$REPO_ROOT"/}"; }

# ── Images ────────────────────────────────────────────────────────────

# The charts default to these; the harness never invents its own tags.
LOOM_IMAGE="${LOOM_IMAGE:-metaloom/loom-server:latest}"
CORTEX_IMAGE="${CORTEX_IMAGE:-metaloom/cortex-server:latest}"

image_present() { docker image inspect "$1" >/dev/null 2>&1; }

# Build via the repository's own scripts. The harness deliberately does not
# reimplement the build — a divergence between what it builds and what the
# release builds would make a green run meaningless.
build_images() {
    section "Building images"
    info "loom-server (loom/containers/build-containers.sh jvm server)"
    (cd "$REPO_ROOT/loom/containers" && ./build-containers.sh jvm server) \
        || fatal "loom-server image build failed"
    info "cortex-server (cortex/container/build-container.sh)"
    (cd "$REPO_ROOT/cortex/container" && ./build-container.sh) \
        || fatal "cortex-server image build failed"
}

require_images() {
    local missing=()
    image_present "$LOOM_IMAGE"   || missing+=("$LOOM_IMAGE")
    image_present "$CORTEX_IMAGE" || missing+=("$CORTEX_IMAGE")

    if (( ${#missing[@]} > 0 )); then
        printf '\n%s\n' "${C_RED}Missing image(s): ${missing[*]}${C_RESET}" >&2
        cat >&2 <<EOF

These are built from the working tree, not pulled from a registry. Either
re-run with --build, or build them yourself first:

  # both need the Maven artifacts and the UI bundle to exist:
  mvn package -DskipTests
  (cd loom-ui && npm run build)

  (cd loom/containers  && ./build-containers.sh jvm server)   # metaloom/loom-server
  (cd cortex/container && ./build-container.sh)               # metaloom/cortex-server

The cortex image additionally needs a local OpenCV 5.1 build; see
cortex/container/build-container.sh (OPENCV_LIB_DIR).
EOF
        exit 1
    fi
    log "$LOOM_IMAGE   $(docker image inspect "$LOOM_IMAGE" --format '{{.Size}}' | numfmt --to=iec)"
    log "$CORTEX_IMAGE $(docker image inspect "$CORTEX_IMAGE" --format '{{.Size}}' | numfmt --to=iec)"
}

# ── Cluster ───────────────────────────────────────────────────────────

cluster_exists() { k3d cluster list -o json 2>/dev/null | jq -e --arg n "$CLUSTER_NAME" '.[] | select(.name == $n)' >/dev/null; }

# The shared media volume for the multi-replica / cross-node phases.
#
# A Docker *named* volume, not a host path: k3d mounts it into every node with
# `@all`, so a hostPath volume inside the cluster is backed by the same storage on
# each node — the RWX behaviour local-path cannot give us. A named volume also
# needs no host path at all, which matters because the harness may itself run in a
# container whose paths do not match the daemon's.
#
# Populated by piping the fixture through `docker run -i`, for the same reason.
shared_media_volume_prepare() {
    docker volume rm "$SHARED_MEDIA_VOLUME" >/dev/null 2>&1 || true
    docker volume create "$SHARED_MEDIA_VOLUME" >/dev/null \
        || fatal "could not create the shared media volume"

    docker run --rm -i -v "$SHARED_MEDIA_VOLUME:/dst" alpine:3 \
        sh -c 'cat > /dst/sample.jpg && chown -R 1000:0 /dst && chmod -R u+rwX,g+rwX,o+rX /dst' \
        < "$SCRIPT_DIR/fixtures/media/sample.jpg" \
        || fatal "could not populate the shared media volume"
    log "shared media volume '$SHARED_MEDIA_VOLUME' populated"
}

shared_media_volume_remove() {
    docker volume rm "$SHARED_MEDIA_VOLUME" >/dev/null 2>&1 || true
}

# Multi-node by default: one server plus two agents.
#
# It costs about 20 seconds and buys real scheduling. The catch is that
# local-path PVs are ReadWriteOnce *and* node-pinned, so anything sharing a
# volume across pods has to either land on one node (the scheduler honours the
# PV's node affinity, which is why the single-replica media claim still works) or
# use the shared named volume above. That is exactly the split the phases use:
# core runs on the media PVC, the scale-out phase switches to the shared volume.
cluster_up() {
    if cluster_exists; then
        if [[ "$REUSE_CLUSTER" == "true" ]]; then
            info "reusing existing cluster '$CLUSTER_NAME'"
            k3d kubeconfig get "$CLUSTER_NAME" > "$KUBECONFIG" 2>/dev/null \
                || fatal "cluster '$CLUSTER_NAME' exists but its kubeconfig could not be read"
            return 0
        fi
        info "deleting stale cluster '$CLUSTER_NAME'"
        k3d cluster delete "$CLUSTER_NAME" >/dev/null 2>&1 || true
    fi

    shared_media_volume_prepare

    info "creating k3d cluster '$CLUSTER_NAME' (1 server + $CLUSTER_AGENTS agents)"
    # Traefik stays enabled — the ingress phase drives a real request through it —
    # and the loadbalancer's :80 is published so that request can come from here.
    # metrics-server is still dead weight.
    k3d cluster create "$CLUSTER_NAME" \
        --servers 1 --agents "$CLUSTER_AGENTS" \
        -p "${INGRESS_LOCAL_PORT}:80@loadbalancer" \
        --volume "${SHARED_MEDIA_VOLUME}:${SHARED_MEDIA_PATH}@all" \
        --k3s-arg "--disable=metrics-server@server:0" \
        --wait --timeout 420s >/dev/null \
        || fatal "k3d cluster creation failed"

    k3d kubeconfig get "$CLUSTER_NAME" > "$KUBECONFIG" \
        || fatal "could not write kubeconfig"
    chmod 600 "$KUBECONFIG"

    wait_for 180 "all nodes Ready" \
        kubectl wait --for=condition=Ready node --all --timeout=10s \
        || fatal "cluster nodes never became Ready"

    kubectl create namespace "$NAMESPACE" >/dev/null 2>&1 || true
    log "cluster ready — $(kubectl get nodes --no-headers | wc -l) nodes, $(kubectl get nodes -o jsonpath='{.items[0].status.nodeInfo.kubeletVersion}')"
}

cluster_down() {
    if cluster_exists; then
        info "deleting cluster '$CLUSTER_NAME'"
        k3d cluster delete "$CLUSTER_NAME" >/dev/null 2>&1 || true
    fi
    shared_media_volume_remove
    rm -f "$KUBECONFIG"
}

# k3d image import copies from the local daemon into the node's containerd, which
# is why the charts can run with pullPolicy: Never and no registry anywhere.
import_images() {
    section "Importing images into the cluster"
    info "this copies ~2.6GB into containerd and takes a minute"
    k3d image import -c "$CLUSTER_NAME" "$LOOM_IMAGE" "$CORTEX_IMAGE" >/dev/null 2>&1 \
        || fatal "k3d image import failed"
    log "imported $LOOM_IMAGE and $CORTEX_IMAGE"

    # The import cycles a helper container through the cluster and can bounce the
    # serverlb, which drops the API connection and can move its published port.
    # Re-reading the kubeconfig and waiting for /readyz turns an intermittent
    # "Kubernetes cluster unreachable" a few seconds later into a non-event.
    k3d kubeconfig get "$CLUSTER_NAME" > "$KUBECONFIG" 2>/dev/null || true
    wait_for 120 "the API server after image import" kubectl get --raw /readyz \
        || fatal "the API server did not come back after the image import"
}
