#!/bin/bash

set -o nounset
set -o errexit

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. Build the project
cd $SCRIPT_DIR
time mvn -T 8 clean package -DskipTests

# 2. UI
cd $SCRIPT_DIR/loom-ui
time npm run build

# 3. Build the container images
cd $SCRIPT_DIR/loom/containers && time ./build-containers.sh all

# 4. Build the cortex container image
echo "Building metaloom/cortex-server:latest ..."
cd $SCRIPT_DIR/cortex/container && time ./build-container.sh