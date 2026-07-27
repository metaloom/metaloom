#!/bin/bash

set -o nounset
set -o errexit

SCRIPT=$(realpath $0)
BASEDIR=$(dirname $SCRIPT)

echo "Building css"
cd $BASEDIR/themes/meghna-hugo
if command -v yarn >/dev/null 2>&1; then
	yarn install && yarn build
else
	echo "yarn not found; falling back to npm"
	npm install && npm run build
fi


cd $BASEDIR
hugo


# The published site must never link back to the build machine. Prose and code samples may of
# course mention a local address (the demo container is documented as localhost:8092) — only
# resource/link attributes are rejected, since those are what a visitor's browser would follow.
# In AsciiDoc, suppress the automatic linking of such a URL by escaping it: `\http://localhost:8092`.
echo "Checking build output for localhost links"
LINK_PATTERN='(href|src|srcset|action|data-src|data-openapi-url|data-graphql-url|data-schema-url)="[^"]*(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\])[^"]*"'
LOCALHOST_LINKS=$(grep -rInoE "$LINK_PATTERN" "$BASEDIR/dist" --include='*.html' --include='*.xml' || true)
if [ -n "$LOCALHOST_LINKS" ]; then
	echo "" >&2
	echo "ERROR: the build output contains links pointing at localhost:" >&2
	echo "$LOCALHOST_LINKS" | sed "s|^$BASEDIR/dist/|  dist/|" >&2
	echo "" >&2
	echo "Mentioning a local address in documentation is fine, linking to one is not." >&2
	echo "Escape the URL in the source (\\http://localhost:8092) or make the link site-relative." >&2
	exit 1
fi


# Every internal link and resource in the output must resolve to something the published site
# actually serves — a 404 on metaloom.io is a bug, not a content detail. The checker also verifies
# "#anchor" fragments, which is how renamed headings silently break cross-page links.
echo "Checking build output for broken internal links"
node "$BASEDIR/check-links.mjs" "$BASEDIR/dist"


echo "All done"
