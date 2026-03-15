#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

rm -rf dist
mkdir -p dist

clojure -M:dev -m shadow.cljs.devtools.cli release pages

find resources/public -mindepth 1 -maxdepth 1 ! -name test -exec cp -R {} dist/ \;
