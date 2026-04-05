#!/usr/bin/env bash
#
# Run the loom-ui end-to-end tests against a running Loom backend.
#
# Usage:
#   ./run-e2e.sh                                  # backend at localhost:8092
#   ./run-e2e.sh http://localhost:9999/api/v1     # custom backend URL
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOOM_UI_DIR="${LOOM_UI_DIR:-$SCRIPT_DIR/../loom-ui}"

API_BASE_URL="${1:-http://localhost:8092/api/v1}"

echo "==> loom-ui dir: $LOOM_UI_DIR"
echo "==> Running loom-ui e2e tests against $API_BASE_URL"

cd "$LOOM_UI_DIR"
VITE_API_BASE_URL="$API_BASE_URL" npx playwright test e2e/login-backend.spec.ts --reporter=list
