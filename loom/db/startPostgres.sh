#!/bin/bash

docker run \
  --name postgres \
   -p 5432:5432 \
   -v /opt/postgres:/var/lib/postgresql/data \
  --rm \
   -e POSTGRES_PASSWORD=finger \
      postgres:13.2
