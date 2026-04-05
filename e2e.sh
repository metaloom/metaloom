#!/bin/bash

set -o nounset
set -o errexit

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. Build the project
cd $SCRIPT_DIR
mvn clean package -DskipTests -pl loom/containers/demo -am

# 2. Build the container image
cd $SCRIPT_DIR/loom/containers && ./build-containers.sh demo

# 3. Run e2e tests
cd $SCRIPT_DIR/e2e-test
mvn test
