#!/bin/bash

NAME=postgres-demo
IMAGE=postgres:16.3-bullseye

podman rm -f $NAME
podman run -d \
        --network dev \
        --name $NAME \
        -e POSTGRES_PASSWORD=finger \
        -p 5444:5432 \
        $IMAGE

createdb -h 127.0.0.1 -p 5444 -U postgres -W loom
