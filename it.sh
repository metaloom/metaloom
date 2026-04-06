#!/bin/bash

set -o nounset
set -o errexit

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. Ensure test-database is running
echo "Starting test-database via podman-compose..."
cd "$SCRIPT_DIR/test-database"
podman-compose up -d

# Wait briefly for the database and provider to become ready
echo "Waiting for test database provider to become ready..."
for i in $(seq 1 30); do
    if curl -sf http://localhost:7543/ > /dev/null 2>&1; then
        echo "Test database provider is ready."
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "ERROR: Test database provider did not become ready in time."
        exit 1
    fi
    sleep 2
done

# 2. Run the PoolSetupRunner to populate the test database pool
echo "Running PoolSetupRunner..."
cd "$SCRIPT_DIR"
mvn exec:java -pl loom/fixture -Dexec.mainClass="io.metaloom.loom.test.PoolSetupRunner"

# 3. Run integration tests
echo "Running integration tests..."
cd "$SCRIPT_DIR"
mvn verify -pl integration-test
