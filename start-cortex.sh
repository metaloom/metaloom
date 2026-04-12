#!/bin/bash

# Starts the Cortex server container image

docker run --network dev \
    -p 8093:8093 \
    -e LOOM_HOST=loom \
    -e LOOM_PORT=8092 \
    metaloom/cortex-server:latest
