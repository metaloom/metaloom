#!/bin/bash

# Starts the build container image

podman run --network dev \
    -p 8092:8092 \
    -e LOOM_INITIAL_PASSWORD=finger \
    -e LOOM_DB_USERNAME=loom \
    -e LOOM_DB_PASSWORD=loom \
    localhost/metaloom/loom-server:latest
