#!/usr/bin/env bash
set -euo pipefail

npm_version='12.2.3'
npm_url='https://registry.npmjs.org/npm/-/npm-12.2.3.tgz'
npm_sha512='a2f98d4517bbe89bdca7513a88e78f43a1fd66be1730eaea81f69e18d86d46f3f75d29762e8fdaf42d4de7f8d194b2ebb2a09e9bfa268067591e96796baca7d5'
work_dir="$(mktemp -d)"
npm_tarball="$work_dir/npm-${npm_version}.tgz"
trap 'rm -f "$npm_tarball"; rmdir "$work_dir"' EXIT

curl --fail --location --silent --show-error --retry 3 --retry-delay 2 --max-time 90 \
  --output "$npm_tarball" "$npm_url"
printf '%s  %s\n' "$npm_sha512" "$npm_tarball" | sha512sum --check --status

npm install --global --ignore-scripts --no-audit --no-fund "$npm_tarball"
test "$(npm --version)" = "$npm_version"

NPM_BUNDLED_MODULES="$(npm root --global)/npm/node_modules" node -e '
  const path = require("node:path");
  const expected = new Map([["brace-expansion", "5.0.12"], ["undici", "6.29.1"]]);
  for (const [name, version] of expected) {
    const actual = require(path.join(process.env.NPM_BUNDLED_MODULES, name, "package.json")).version;
    if (actual !== version) throw new Error(`${name}: expected ${version}, found ${actual}`);
  }
'
