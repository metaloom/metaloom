#!/bin/bash

set -euo pipefail

NAME=postgres-demo
IMAGE=postgres:16.3-bullseye
NETWORK=dev

docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK"
docker rm -f "$NAME" >/dev/null 2>&1 || true

docker run -d \
                --network "$NETWORK" \
                --name "$NAME" \
        -e POSTGRES_PASSWORD=finger \
        -p 5444:5432 \
                "$IMAGE"

echo "Waiting for postgres to accept connections..."
until docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1; do
        sleep 1
done

docker exec "$NAME" psql -U postgres -tc "SELECT 1 FROM pg_database WHERE datname='loom'" | grep -q 1 \
        || docker exec "$NAME" psql -U postgres -c "CREATE DATABASE loom"

echo "Postgres is ready on 127.0.0.1:5444"
