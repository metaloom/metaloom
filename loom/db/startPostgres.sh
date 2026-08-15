#!/bin/bash

# PGDATA is set explicitly because the postgres:18 image moved its default from
# /var/lib/postgresql/data to /var/lib/postgresql/18/docker. Without this the bind
# mount below still succeeds and the server still starts - it just writes the cluster
# into the container layer, which --rm then throws away.
#
# A /opt/postgres directory left over from a postgres:16 container cannot be started by
# 18: a major version bump needs pg_upgrade, or a fresh directory.
docker run \
  --name postgres \
   -p 5432:5432 \
   -v /opt/postgres:/var/lib/postgresql/data \
   -e PGDATA=/var/lib/postgresql/data \
  --rm \
   -e POSTGRES_PASSWORD=finger \
      postgres:18.6-trixie
