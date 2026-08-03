#!/usr/bin/env bash
# Run the test suite.
#
# Falls back to the system python when there is no venv: the client has no runtime
# dependencies, so the unit tests run anywhere without a setup step.
#
# The integration tests are skipped unless LOOM_IT=1. They need a running server:
#
#     ../../start-postgres.sh && ../../start-demo.sh
#     LOOM_IT=1 ./test.sh
set -euo pipefail
cd "$(dirname "$0")"

if [ -x ./.venv/bin/python ]; then
	PYTHON=./.venv/bin/python
else
	PYTHON="${PYTHON:-python3}"
fi

exec "$PYTHON" -m unittest discover -s . -t . "$@"
