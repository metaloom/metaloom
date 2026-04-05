#!/usr/bin/env bash
#
# Run the loom-ui end-to-end tests against a running Loom backend.
#
# Usage:
#   ./run-e2e.sh                                  # backend at localhost:8092
#   ./run-e2e.sh http://localhost:9999            # custom backend base URL
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOOM_UI_DIR="${LOOM_UI_DIR:-$SCRIPT_DIR/../loom-ui}"

PROXY_TARGET="${1:-http://localhost:8092}"

echo "==> loom-ui dir: $LOOM_UI_DIR"
echo "==> Running loom-ui e2e tests (proxy target: $PROXY_TARGET)"

cd "$LOOM_UI_DIR"
VITE_API_BASE_URL="/api/v1" VITE_PROXY_TARGET="$PROXY_TARGET" npx playwright test e2e/login-backend.spec.ts --reporter=list
