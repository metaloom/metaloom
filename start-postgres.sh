#!/bin/bash

NAME=postgres-demo
IMAGE=postgres:16.3-bullseye

docker network create dev
docker rm -f $NAME
docker run -d \
        --network dev \
        --name $NAME \
        -e POSTGRES_PASSWORD=finger \
        -p 5444:5432 \
        $IMAGE

createdb -h 127.0.0.1 -p 5444 -U postgres -W loom
