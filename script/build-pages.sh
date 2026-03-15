#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

rm -rf dist
mkdir -p dist

clojure -M:dev -m shadow.cljs.devtools.cli release pages

cp resources/public/index.html dist/index.html
cp resources/public/index.css dist/index.css
cp resources/public/graph.js dist/graph.js
