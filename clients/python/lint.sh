#!/usr/bin/env bash
# Lint and format-check this directory only.
#
# Configuration lives in pyproject.toml. Nothing else in the repository is linted,
# and this is deliberately not wired into build.sh, Maven or CI.
set -euo pipefail
cd "$(dirname "$0")"

if [ -x ./.venv/bin/ruff ]; then
	RUFF=./.venv/bin/ruff
elif command -v ruff >/dev/null 2>&1; then
	RUFF=ruff
else
	echo "ruff is not installed. Run ./setup.sh first." >&2
	exit 1
fi

"$RUFF" check .
"$RUFF" format --check .
