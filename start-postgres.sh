#!/bin/bash

set -euo pipefail

NAME=postgres-demo
IMAGE=postgres:18.6-trixie
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
# Probe over TCP, and with a real query.
#
# While it initialises the data directory the image runs a temporary server that listens on the unix
# socket ONLY, and then shuts it down before starting the real one. Both pg_isready and a plain psql
# talk to that socket by default, so either will happily report "ready" against the throwaway server -
# and the next command then finds the socket gone. Forcing -h 127.0.0.1 skips it: the temporary server
# is not reachable over TCP, so the first successful TCP query is the real server.
ready=false
for _ in $(seq 1 60); do
        if docker exec "$NAME" psql -U postgres -h 127.0.0.1 -c "SELECT 1" >/dev/null 2>&1; then
                ready=true
                break
        fi
        sleep 1
done

if [ "$ready" != true ]; then
        echo "Postgres did not become ready within 60s" >&2
        docker logs --tail 50 "$NAME" >&2 || true
        exit 1
fi

docker exec "$NAME" psql -U postgres -h 127.0.0.1 -tc "SELECT 1 FROM pg_database WHERE datname='loom'" | grep -q 1 \
        || docker exec "$NAME" psql -U postgres -h 127.0.0.1 -c "CREATE DATABASE loom"

echo "Postgres is ready on 127.0.0.1:5444"
