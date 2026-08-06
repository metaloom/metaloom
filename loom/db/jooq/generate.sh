#!/bin/bash
#
# Regenerates the jOOQ sources under src/jooq/java.
#
# Codegen runs into a scratch directory first and the checked-in sources are only
# replaced once it has succeeded. A failing migration or codegen step therefore
# leaves the working tree untouched instead of deleting every generated class.
#
set -euo pipefail

cd "$(dirname "$0")"

OUT_DIR="src/jooq/java"
TMP_DIR="target/jooq-codegen"

rm -rf "$TMP_DIR"
mkdir -p "$TMP_DIR"

mvn groovy:2.1.1:execute@testcontainer-start flyway:9.14.1:migrate@default jooq-codegen:3.17.8:generate@jooq-codegen -Dgenerate -DskipTests -Djooq.output.dir="$TMP_DIR"

if [ -z "$(ls -A "$TMP_DIR")" ]; then
	echo "Codegen produced no sources in $TMP_DIR - keeping $OUT_DIR as is." >&2
	exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$(dirname "$OUT_DIR")"
mv "$TMP_DIR" "$OUT_DIR"

echo "Generated $(find "$OUT_DIR" -name '*.java' | wc -l) sources into $OUT_DIR"
