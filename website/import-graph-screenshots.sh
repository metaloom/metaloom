#!/usr/bin/env bash
#
# Copies the MetaLoom // Graph browser screenshots into this site's page bundle.
#
#   ./import-graph-screenshots.sh [path-to-metaloom-graph]
#
# Capture them first, in the graph repository:
#
#   cd graph-server/src/main/frontend && npm run screenshots
#
# The two repositories are separate, and this script is the seam. Neither build requires the other's checkout to be
# present: the graph repo writes into its own target/, this copies from there when somebody asks, and the images are
# committed here so a website build never needs the graph repo at all.
#
set -euo pipefail

SITE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRAPH="${1:-$SITE/../../graph-storage-ffm-poc}"
SRC="$GRAPH/graph-server/target/screenshots"
DEST="$SITE/content/english/graph/browser"

if [ ! -d "$SRC" ]; then
	echo "No screenshots at $SRC" >&2
	echo "Capture them first:  cd $GRAPH/graph-server/src/main/frontend && npm run screenshots" >&2
	exit 1
fi

mkdir -p "$DEST"
copied=0
for png in "$SRC"/*.png; do
	[ -e "$png" ] || continue
	cp "$png" "$DEST/"
	copied=$((copied + 1))
	echo "  $(basename "$png")"
done

if [ "$copied" -eq 0 ]; then
	echo "No PNGs in $SRC" >&2
	exit 1
fi

echo "$copied screenshot(s) imported into content/english/graph/browser/"
echo "check-graph-screenshots.mjs (run by build.sh) checks they are all referenced with real alt text."
