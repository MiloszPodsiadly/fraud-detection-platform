#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace="$(git -C "$script_directory/../../.." rev-parse --show-toplevel)"
policy="${workspace}/scripts/security/run-security-gate.mjs"
npm_version="$(node "$policy" policy --get tools.npm)"
npm_url="https://registry.npmjs.org/npm/-/npm-${npm_version}.tgz"
npm_sha512="$(node "$policy" policy --get downloadIntegrity.npmArchiveSha512)"
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
