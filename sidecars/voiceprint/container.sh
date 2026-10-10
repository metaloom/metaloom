#!/usr/bin/env bash
# Build, run and test the voiceprint sidecar container. docker or podman.
#
#   ./container.sh build            build the image
#   ./container.sh run              start it detached and wait until /health answers
#   ./container.sh test             unit tests + live tests inside the running container
#   ./container.sh logs | stop
#
# Environment:
#   VOICEPRINT_IMAGE       image tag                              (default: metaloom/voiceprint-sidecar:latest)
#   VOICEPRINT_NAME        container name                         (default: voiceprint-sidecar)
#   VOICEPRINT_PORT        host port                              (default: 9150)
#   VOICEPRINT_GPU         GPU selector: all | 0 | 1 | none       (default: all)
#   VOICEPRINT_HF_CACHE    host HF cache to mount                 (default: $HOME/.cache/huggingface)
#   VOICEPRINT_TORCH_CACHE host torch hub cache to mount          (default: $HOME/.cache/torch)
#   VOICEPRINT_RUNTIME     docker | podman                        (default: whichever is installed)
#   Any other VOICEPRINT_* variable (VOICEPRINT_BACKENDS, VOICEPRINT_PRELOAD, ...) is passed into the container.
set -euo pipefail
cd "$(dirname "$0")"

IMAGE="${VOICEPRINT_IMAGE:-metaloom/voiceprint-sidecar:latest}"
NAME="${VOICEPRINT_NAME:-voiceprint-sidecar}"
PORT="${VOICEPRINT_PORT:-9150}"
GPU="${VOICEPRINT_GPU:-all}"
HF_CACHE="${VOICEPRINT_HF_CACHE:-$HOME/.cache/huggingface}"
TORCH_CACHE="${VOICEPRINT_TORCH_CACHE:-$HOME/.cache/torch}"
RUNTIME="${VOICEPRINT_RUNTIME:-$(command -v docker >/dev/null && echo docker || echo podman)}"

gpu_args() {
  [ "$GPU" = "none" ] && return
  # CDI (nvidia-container-toolkit >= 1.14) works for both runtimes and is preferred: docker's legacy
  # --gpus hook enforces the image's CUDA requirement against the driver and refuses images the CDI
  # path starts fine. Ask the daemon rather than looking for /etc/cdi - with a remote or namespaced
  # daemon the spec directory is not visible from here.
  if [ -d /etc/cdi ] || [ -d /var/run/cdi ] || "$RUNTIME" info 2>/dev/null | grep -q "nvidia.com/gpu="; then
    echo "--device nvidia.com/gpu=${GPU}"
  elif [ "$RUNTIME" = docker ]; then
    [ "$GPU" = all ] && echo "--gpus all" || echo "--gpus device=${GPU}"
  else
    echo "--device nvidia.com/gpu=${GPU}"
  fi
}

env_args() {
  # Forward VOICEPRINT_* settings, except the ones that only configure this script.
  env | grep -E '^VOICEPRINT_' | grep -v -E '^VOICEPRINT_(IMAGE|NAME|GPU|HF_CACHE|TORCH_CACHE|RUNTIME|PORT|TEST_)' | sed 's/^/-e /' | tr '\n' ' '
}

case "${1:-}" in
  build)
    exec "$RUNTIME" build -t "$IMAGE" .
    ;;
  run)
    "$RUNTIME" rm -f "$NAME" >/dev/null 2>&1 || true
    # Best effort: with a remote daemon the path is on its host, not here.
    mkdir -p "$HF_CACHE" "$TORCH_CACHE" 2>/dev/null || true
    # shellcheck disable=SC2046
    "$RUNTIME" run -d --name "$NAME" $(gpu_args) $(env_args) \
      -p "${PORT}:9150" \
      -v "${HF_CACHE}:/root/.cache/huggingface" \
      -v "${TORCH_CACHE}:/root/.cache/torch" \
      "$IMAGE" >/dev/null
    echo "started $NAME ($IMAGE) on :$PORT - waiting for /health"
    for _ in $(seq 1 120); do
      if curl -fs "http://localhost:${PORT}/health" >/dev/null 2>&1; then
        curl -fs "http://localhost:${PORT}/health"; echo
        exit 0
      fi
      if [ "$("$RUNTIME" inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" != "true" ]; then
        "$RUNTIME" logs "$NAME" | tail -40
        echo "container exited" >&2
        exit 1
      fi
      sleep 2
    done
    echo "no /health after 240 s - see: $0 logs" >&2
    exit 1
    ;;
  test)
    # Unit tests need no model; the live tests load every backend on first use (a minute, cold).
    "$RUNTIME" exec "$NAME" python3 -m pytest tests -q -p no:cacheprovider --ignore=tests/test_live.py
    exec "$RUNTIME" exec -e VOICEPRINT_TEST_URL=http://localhost:9150 \
      -e VOICEPRINT_TEST_MODELS="${VOICEPRINT_TEST_MODELS:-redimnet2,wespeaker,speechbrain}" \
      "$NAME" python3 -m pytest tests/test_live.py -v -s -p no:cacheprovider
    ;;
  logs)
    exec "$RUNTIME" logs -f "$NAME"
    ;;
  stop)
    exec "$RUNTIME" rm -f "$NAME"
    ;;
  *)
    sed -n '2,17p' "$0"
    exit 2
    ;;
esac
