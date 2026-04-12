#!/bin/bash

# Starts the build container image

docker run --network dev \
    -p 8092:8092 \
    -e LOOM_INITIAL_PASSWORD=finger \
    -e LOOM_DB_USERNAME=postgres \
    -e LOOM_DB_PASSWORD=finger \
    -e LOOM_DB_NAME=loom \
    -e LOOM_DB_HOST=postgres-demo \
    -e LOOM_DB_PORT=5432 \
    metaloom/loom-demo:latest
