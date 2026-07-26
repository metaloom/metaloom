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


echo "All done"
