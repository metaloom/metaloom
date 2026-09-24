#!/usr/bin/env bash
# Build, run and test the ASR sidecar container. docker or podman.
#
#   ./container.sh build            build the image
#   ./container.sh run              start it detached and wait until /health answers
#   ./container.sh test             unit tests + live tests inside the running container
#   ./container.sh logs | stop
#
# Environment:
#   ASR_IMAGE      image tag                              (default: metaloom/asr-sidecar:latest)
#   ASR_NAME       container name                         (default: asr-sidecar)
#   ASR_PORT       host port                              (default: 9140)
#   ASR_GPU        GPU selector: all | 0 | 1 | none       (default: all)
#   ASR_HF_CACHE   host HF cache to mount                 (default: $HOME/.cache/huggingface)
#   ASR_RUNTIME    docker | podman                        (default: whichever is installed)
#   Any other ASR_* variable (ASR_BACKENDS, ASR_PRELOAD, ...) is passed into the container.
set -euo pipefail
cd "$(dirname "$0")"

IMAGE="${ASR_IMAGE:-metaloom/asr-sidecar:latest}"
NAME="${ASR_NAME:-asr-sidecar}"
PORT="${ASR_PORT:-9140}"
GPU="${ASR_GPU:-all}"
HF_CACHE="${ASR_HF_CACHE:-$HOME/.cache/huggingface}"
RUNTIME="${ASR_RUNTIME:-$(command -v docker >/dev/null && echo docker || echo podman)}"

gpu_args() {
  [ "$GPU" = "none" ] && return
  # CDI (nvidia-container-toolkit >= 1.14) works for both runtimes; --gpus is docker's legacy flag.
  if [ -d /etc/cdi ] || [ -d /var/run/cdi ]; then
    echo "--device nvidia.com/gpu=${GPU}"
  elif [ "$RUNTIME" = docker ]; then
    [ "$GPU" = all ] && echo "--gpus all" || echo "--gpus device=${GPU}"
  else
    echo "--device nvidia.com/gpu=${GPU}"
  fi
}

env_args() {
  # Forward ASR_* settings, except the ones that only configure this script.
  env | grep -E '^ASR_' | grep -v -E '^ASR_(IMAGE|NAME|GPU|HF_CACHE|RUNTIME|PORT|TEST_)' | sed 's/^/-e /' | tr '\n' ' '
}

case "${1:-}" in
  build)
    exec "$RUNTIME" build -t "$IMAGE" .
    ;;
  run)
    "$RUNTIME" rm -f "$NAME" >/dev/null 2>&1 || true
    # Best effort: with a remote daemon the path is on its host, not here.
    mkdir -p "$HF_CACHE" 2>/dev/null || true
    # shellcheck disable=SC2046
    "$RUNTIME" run -d --name "$NAME" $(gpu_args) $(env_args) \
      -p "${PORT}:9140" \
      -v "${HF_CACHE}:/root/.cache/huggingface" \
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
    # Unit tests need no model; the live tests load every backend on first use (minutes, cold).
    "$RUNTIME" exec "$NAME" python3 -m pytest tests -q -p no:cacheprovider --ignore=tests/test_live.py
    exec "$RUNTIME" exec -e ASR_TEST_URL=http://localhost:9140 \
      -e ASR_TEST_MODELS="${ASR_TEST_MODELS:-whisper,parakeet,voxtral}" -e ASR_TEST_PACE="${ASR_TEST_PACE:-1.0}" \
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
