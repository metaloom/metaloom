#!/bin/bash

set -o nounset
set -o errexit

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. Build the project
cd $SCRIPT_DIR
mvn clean package -DskipTests

# 2. UI
cd $SCRIPT_DIR/loom-ui
npm run build

# 3. Build the container image
cd $SCRIPT_DIR/loom/containers && ./build-containers.sh all
