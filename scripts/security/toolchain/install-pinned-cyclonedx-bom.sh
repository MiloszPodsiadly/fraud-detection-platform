#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace="$(git -C "$script_directory/../../.." rev-parse --show-toplevel)"
policy="${workspace}/scripts/security/run-security-gate.mjs"
tool_version="$(node "$policy" policy --get tools.cyclonedxBom)"
wheel_name="cyclonedx_bom-${tool_version}-py3-none-any.whl"
wheel_sha256="$(node "$policy" policy --get downloadIntegrity.cyclonedxWheelSha256)"
python_bin="${PYTHON:-python}"
tool_dir="$(mktemp -d)"
wheel_path="$tool_dir/$wheel_name"
trap 'rm -rf "$tool_dir"' EXIT

"$python_bin" -m pip download --no-deps --only-binary=:all: --dest "$tool_dir" "cyclonedx-bom==$tool_version"
test -f "$wheel_path"
printf '%s  %s\n' "$wheel_sha256" "$wheel_path" | sha256sum --check --status

"$python_bin" -m pip install --only-binary=:all: --no-input "$wheel_path"
CYCLONEDX_BOM_VERSION="$tool_version" "$python_bin" -c 'import importlib.metadata, os; actual = importlib.metadata.version("cyclonedx-bom"); expected = os.environ["CYCLONEDX_BOM_VERSION"]; assert actual == expected, f"expected cyclonedx-bom {expected}, found {actual}"'
