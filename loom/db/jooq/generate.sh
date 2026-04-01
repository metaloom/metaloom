#!/bin/bash

rm -rf src/jooq/java/
mvn groovy:2.1.1:execute@testcontainer-start flyway:9.14.1:migrate@default jooq-codegen:3.17.8:generate@jooq-codegen -Dgenerate -DskipTests
