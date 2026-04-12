#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cleanup() {
	docker rm -f loom-demo postgres-demo >/dev/null 2>&1 || true
}

trap cleanup EXIT

# 1. Build the project
cd "$SCRIPT_DIR"
mvn clean package -DskipTests -pl loom/containers/demo -am

# 2. Build the container image
cd "$SCRIPT_DIR/loom/containers"
./build-containers.sh demo

# 3. Start PostgreSQL + demo containers
cd "$SCRIPT_DIR"
./start-postgres.sh
./start-demo.sh

# 4. Run e2e tests against external backend container
cd "$SCRIPT_DIR/e2e-test"
mvn test -Dloom.external=true
