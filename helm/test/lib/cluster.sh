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

# One node on purpose.
#
# Loom's uploads volume and the media volume are ReadWriteOnce, which is all the
# local-path provisioner offers. A second node would let the scheduler place
# Loom and Cortex apart and leave one of them unable to bind — a k3d artefact,
# not a chart bug. Multi-node belongs in a test with a RWX StorageClass.
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

    info "creating single-node k3d cluster '$CLUSTER_NAME'"
    # Traefik and metrics-server are dead weight here: nothing in these charts is
    # exercised through an Ingress, and the API is reached by port-forward.
    k3d cluster create "$CLUSTER_NAME" \
        --servers 1 \
        --k3s-arg "--disable=traefik@server:0" \
        --k3s-arg "--disable=metrics-server@server:0" \
        --wait --timeout 300s >/dev/null \
        || fatal "k3d cluster creation failed"

    k3d kubeconfig get "$CLUSTER_NAME" > "$KUBECONFIG" \
        || fatal "could not write kubeconfig"
    chmod 600 "$KUBECONFIG"

    wait_for 120 "node Ready" \
        kubectl wait --for=condition=Ready node --all --timeout=10s \
        || fatal "cluster node never became Ready"

    kubectl create namespace "$NAMESPACE" >/dev/null 2>&1 || true
    log "cluster ready — $(kubectl get nodes -o jsonpath='{.items[0].status.nodeInfo.kubeletVersion}')"
}

cluster_down() {
    if cluster_exists; then
        info "deleting cluster '$CLUSTER_NAME'"
        k3d cluster delete "$CLUSTER_NAME" >/dev/null 2>&1 || true
    fi
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
