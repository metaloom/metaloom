#!/usr/bin/env bash
# Create the virtualenv and install the client into it in editable mode.
#
# The client itself has no dependencies; the venv exists so that ruff and the
# editable install stay out of the system Python.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"

if [ ! -d .venv ]; then
	"$PYTHON" -m venv .venv
fi
./.venv/bin/pip install --upgrade pip
./.venv/bin/pip install -e ".[dev]"

echo
echo "Ready. Run the tests with ./test.sh"
