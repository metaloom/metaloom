#!/usr/bin/env bash
#
# Stop the llama.cpp sidecar started by run.sh.
#
set -euo pipefail

NAME="${LLAMACPP_NAME:-loom-llamacpp}"

RUNTIME="${LLAMACPP_RUNTIME:-}"
if [ -z "$RUNTIME" ]; then
	for candidate in docker podman; do
		if command -v "$candidate" >/dev/null 2>&1; then
			RUNTIME="$candidate"
			break
		fi
	done
fi
if [ -z "$RUNTIME" ]; then
	echo "Neither docker nor podman found in PATH. Install one, or set LLAMACPP_RUNTIME." >&2
	exit 1
fi

"$RUNTIME" rm -f "$NAME" >/dev/null 2>&1 || true
echo "Stopped $NAME"
