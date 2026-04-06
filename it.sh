#!/bin/bash

set -o nounset
set -o errexit

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. Run the PoolSetupRunner to populate the test database pool
echo "Running PoolSetupRunner..."
cd "$SCRIPT_DIR"
mvn exec:java -pl loom/fixture -Dexec.mainClass="io.metaloom.loom.test.PoolSetupRunner"

# 2. Run integration tests
echo "Running integration tests..."
cd "$SCRIPT_DIR"
mvn verify -pl integration-test
