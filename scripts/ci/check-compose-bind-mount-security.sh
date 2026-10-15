#!/usr/bin/env bash
set -euo pipefail

docker version --format 'Docker Engine: {{.Server.Version}}'
docker compose version

workspace="$(mktemp -d)"
project="exact-model-bind-$RANDOM-$$"
compose_file="$workspace/compose.yml"
artifact_root="$workspace/artifacts"

cleanup() {
  docker compose --project-name "$project" -f "$compose_file" down --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$workspace"
}
trap cleanup EXIT

cat >"$compose_file" <<'YAML'
services:
  reader:
    image: alpine:3.21
    command:
      - /bin/sh
      - -ec
      - |
        test -r /run/model-shadow-performance/probe
        if touch /run/model-shadow-performance/must-not-write 2>/dev/null; then
          echo "read-only artifact mount accepted a write" >&2
          exit 1
        fi
    volumes:
      - type: bind
        source: ${ARTIFACT_ROOT:?ARTIFACT_ROOT is required}
        target: /run/model-shadow-performance
        read_only: true
        bind:
          create_host_path: false
YAML

if ARTIFACT_ROOT="$artifact_root" docker compose --project-name "$project" -f "$compose_file" run --rm --no-deps reader; then
  echo "missing artifact root was accepted" >&2
  exit 1
fi
if [[ -e "$artifact_root" ]]; then
  echo "Compose created the missing artifact root" >&2
  exit 1
fi

mkdir "$artifact_root"
printf 'verified\n' >"$artifact_root/probe"
ARTIFACT_ROOT="$artifact_root" docker compose --project-name "$project" -f "$compose_file" run --rm --no-deps reader

if [[ -e "$artifact_root/must-not-write" ]]; then
  echo "read-only artifact mount changed the host artifact root" >&2
  exit 1
fi

echo "Compose bind-mount security behavior passed."
