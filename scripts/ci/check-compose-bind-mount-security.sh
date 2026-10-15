#!/usr/bin/env bash
set -euo pipefail

docker version --format 'Docker Engine: {{.Server.Version}}'
docker compose version

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workspace="$(mktemp -d)"
project="exact-model-bind-$RANDOM-$$"
compose_file="$workspace/compose.yml"
artifact_root="$workspace/artifacts"
resolved_config="$workspace/resolved.json"

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

MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$artifact_root" docker compose \
  --env-file "$repository_root/deployment/.env.example" \
  -f "$repository_root/deployment/docker-compose.yml" \
  -f "$repository_root/deployment/docker-compose.model-shadow-performance-generated.yml" \
  config --format json >"$resolved_config"

if MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$artifact_root" node \
  "$repository_root/scripts/check-compose-security-config.mjs" \
  model-shadow-performance --require-existing-source <"$resolved_config"; then
  echo "missing artifact root passed the startup preflight" >&2
  exit 1
fi
if [[ -e "$artifact_root" ]]; then
  echo "startup preflight created the missing artifact root" >&2
  exit 1
fi

mkdir "$artifact_root"
printf 'verified\n' >"$artifact_root/probe"
MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$artifact_root" node \
  "$repository_root/scripts/check-compose-security-config.mjs" \
  model-shadow-performance --require-existing-source <"$resolved_config"
probe_sha256="$(sha256sum "$artifact_root/probe" | awk '{print $1}')"
ARTIFACT_ROOT="$artifact_root" docker compose --project-name "$project" -f "$compose_file" \
  up --abort-on-container-exit --exit-code-from reader reader

if [[ -e "$artifact_root/must-not-write" ]]; then
  echo "read-only artifact mount changed the host artifact root" >&2
  exit 1
fi
if [[ "$(sha256sum "$artifact_root/probe" | awk '{print $1}')" != "$probe_sha256" ]]; then
  echo "read-only artifact mount modified the host probe" >&2
  exit 1
fi
if find "$artifact_root" -mindepth 1 -maxdepth 1 ! -name probe -print -quit | grep -q .; then
  echo "read-only artifact mount added an unexpected host artifact" >&2
  exit 1
fi

echo "Compose bind-mount security behavior passed."
