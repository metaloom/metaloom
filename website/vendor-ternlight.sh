#!/bin/bash
#
# Vendors the Ternlight embedding engine into themes/meghna-hugo/static/plugins/ternlight/.
#
# Run this once, by hand, and again only to bump the model. The result is committed — it is a
# build input for build-search-index.mjs, and fetching it at build time would put a network call
# into build.sh, which has none today.
#
# ONE wasm serves both sides. @ternlight/base ships three wasm-pack targets (pkg-web, pkg-node,
# pkg-bundler) whose .wasm files are byte-identical, and the pkg-web glue is self-contained: it
# has no import statements at all (the wasm-bindgen import object is built inline), and its
# initSync() takes raw bytes while its default export takes a URL. So the browser fetches the
# wasm and the index builder reads the same file off disk — index-time and query-time embeddings
# cannot come from different model versions, because there is only one model file.
#
# That is also why there is no bundler here: the site has none, and the "bundler" target — whose
# glue does `import * as wasm from './tern_engine_bg.wasm'` — is the one we deliberately do not use.
#
# Usage: ./vendor-ternlight.sh [version]

set -o nounset
set -o errexit

VERSION="${1:-0.1.1}"
PACKAGE="@ternlight/base"

SCRIPT=$(realpath "$0")
BASEDIR=$(dirname "$SCRIPT")
TARGET="$BASEDIR/themes/meghna-hugo/static/plugins/ternlight"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "Fetching $PACKAGE@$VERSION"
( cd "$WORK" && npm pack "$PACKAGE@$VERSION" >/dev/null && tar xzf ./*.tgz )

SRC="$WORK/package"
[ -f "$SRC/pkg-web/tern_engine.js" ] || {
	echo "ERROR: $PACKAGE@$VERSION has no pkg-web/ build." >&2
	echo "The package layout changed. Do NOT fall back to pkg-bundler/ — its glue imports the" >&2
	echo ".wasm as an ESM asset, which needs a bundler this site does not have." >&2
	exit 1
}

# The three targets must keep shipping the same model, or "one wasm for both hosts" stops holding.
WEB_SUM=$(sha256sum "$SRC/pkg-web/tern_engine_bg.wasm" | cut -d' ' -f1)
NODE_SUM=$(sha256sum "$SRC/pkg-node/tern_engine_bg.wasm" | cut -d' ' -f1)
if [ "$WEB_SUM" != "$NODE_SUM" ]; then
	echo "ERROR: pkg-web and pkg-node ship different wasm files ($WEB_SUM vs $NODE_SUM)." >&2
	echo "The one-model assumption no longer holds; rework the vendoring before continuing." >&2
	exit 1
fi

# The glue must stay import-free, or a plain <script type="module"> cannot load it.
if grep -qE "^\s*import[\s{]" "$SRC/pkg-web/tern_engine.js"; then
	echo "ERROR: pkg-web/tern_engine.js now carries import statements:" >&2
	grep -nE "^\s*import[\s{]" "$SRC/pkg-web/tern_engine.js" >&2
	echo "It can no longer be served directly. Rework the vendoring before continuing." >&2
	exit 1
fi

mkdir -p "$TARGET"
cp "$SRC/pkg-web/tern_engine.js"      "$TARGET/"
cp "$SRC/pkg-web/tern_engine_bg.wasm" "$TARGET/"
cp "$SRC/pkg-web/package.json"        "$TARGET/"
cp "$SRC/LICENSE"                     "$TARGET/"

# pkg-web/tern_engine_bg.js is NOT copied: the web target inlines its import object, so that file
# is dead weight in this layout. Neither are the .d.ts files — nothing here is type-checked.
#
# package.json IS copied, and it is upstream's own one-line {"type":"module"}. A browser ignores
# it, but Node resolves the *nearest* package.json when it loads the glue — and the nearest one
# would otherwise be the theme's, which declares no type, so build-search-index.mjs would eat a
# MODULE_TYPELESS_PACKAGE_JSON reparse warning on every build.

SUMMARY=$(cd "$TARGET" && node --input-type=module -e "
import { readFileSync } from 'node:fs';
import { initSync, config_summary } from './tern_engine.js';
initSync({ module: readFileSync('./tern_engine_bg.wasm') });
process.stdout.write(config_summary());
")

cat > "$TARGET/VERSION" <<EOF
package $PACKAGE@$VERSION
sha256  $WEB_SUM
engine  $SUMMARY
EOF

echo ""
echo "Vendored into themes/meghna-hugo/static/plugins/ternlight/:"
ls -l "$TARGET"
echo ""
echo "  $SUMMARY"
echo ""
echo "Commit these. Then rebuild the search index: ./build.sh"
