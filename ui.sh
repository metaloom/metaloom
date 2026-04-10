#!/bin/bash


set -o nounset
set -o errexit

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"


cd $SCRIPT_DIR/loom-ui
#npm i
npm run dev
