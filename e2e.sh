#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cleanup() {
	# "loom", not "loom-demo": that is the name start-demo.sh gives the container. Removing the wrong
	# name leaked a running Loom on every run, and the leak was not idle - the next run's
	# start-postgres.sh creates a fresh database on the same docker network, the leaked instance
	# reconnects and seeds it, and then start-demo.sh replaces it with a container that seeds the very
	# same database again. The duplicate rows that produced surfaced much later as a 500 from an
	# asset_location unique violation during the binary upload test.
	docker rm -f loom postgres-demo >/dev/null 2>&1 || true
}

trap cleanup EXIT

# 1. Build the project
cd "$SCRIPT_DIR"
mvn clean package -DskipTests -pl loom/containers/demo -am

# 2. Build the container image
# The first argument is the variant, not the target: "demo" alone leaves the variant at its "both"
# default and additionally builds the GraalVM native image, which the tests never start - and which
# aborts the whole run on a machine with no GraalVM installed.
cd "$SCRIPT_DIR/loom/containers"
./build-containers.sh jvm demo

# 3. Start PostgreSQL + demo containers
cd "$SCRIPT_DIR"
./start-postgres.sh
./start-demo.sh

# 4. Run e2e tests against external backend container
cd "$SCRIPT_DIR/e2e-test"
mvn test -Dloom.external=true
