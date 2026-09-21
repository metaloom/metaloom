#!/usr/bin/env bash
#
# Stop the TEI sidecar started by run.sh.
#
set -euo pipefail

NAME="${TEI_NAME:-loom-tei}"

RUNTIME="${TEI_RUNTIME:-}"
if [ -z "$RUNTIME" ]; then
	for candidate in docker podman; do
		if command -v "$candidate" >/dev/null 2>&1; then
			RUNTIME="$candidate"
			break
		fi
	done
fi
if [ -z "$RUNTIME" ]; then
	echo "Neither docker nor podman found in PATH. Install one, or set TEI_RUNTIME." >&2
	exit 1
fi

"$RUNTIME" rm -f "$NAME" >/dev/null 2>&1 || true
echo "Stopped $NAME"
