#!/bin/bash

set -o nounset
set -o errexit

SCRIPT=$(realpath $0)
BASEDIR=$(dirname $SCRIPT)

echo "Building css"
cd $BASEDIR/themes/meghna-hugo
yarn install && yarn build


cd $BASEDIR
hugo


echo "All done"
