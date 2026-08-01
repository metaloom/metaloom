#!/bin/bash
#
# Stops the llama.cpp test server started by start.sh.
#
set -euo pipefail

NAME=${NAME:-loom-test-llamacpp}

docker rm -f "$NAME" >/dev/null 2>&1 || true
echo "Stopped $NAME"
