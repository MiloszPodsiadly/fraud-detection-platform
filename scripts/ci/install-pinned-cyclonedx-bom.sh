#!/usr/bin/env bash
set -euo pipefail

tool_version='9.0.0'
wheel_name="cyclonedx_bom-${tool_version}-py3-none-any.whl"
wheel_sha256='15ccb78616831688cab1d1ddd5e0ba2e7886cd83462f63d11d1b922f43a294c0'
tool_dir="$(mktemp -d)"
wheel_path="$tool_dir/$wheel_name"
trap 'rm -rf "$tool_dir"' EXIT

python -m pip download --no-deps --only-binary=:all: --dest "$tool_dir" "cyclonedx-bom==$tool_version"
test -f "$wheel_path"
printf '%s  %s\n' "$wheel_sha256" "$wheel_path" | sha256sum --check --status

python -m pip install --only-binary=:all: --no-input "$wheel_path"
python -c 'import importlib.metadata; actual = importlib.metadata.version("cyclonedx-bom"); expected = "9.0.0"; assert actual == expected, f"expected cyclonedx-bom {expected}, found {actual}"'
