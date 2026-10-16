#!/usr/bin/env bash
set -euo pipefail

docker version --format 'Docker Engine: {{.Server.Version}}'
docker compose version

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workspace="$(mktemp -d)"
project="exact-model-bind-$RANDOM-$$"
compose_file="$workspace/compose.yml"
artifact_root="$workspace/artifacts"
preflight_artifact_root="$workspace/preflight-artifacts"
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
    label_file:
      - ${ARTIFACT_ROOT:?ARTIFACT_ROOT is required}/.artifact-root-ready.labels
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

mkdir "$preflight_artifact_root"
printf 'com.frauddetection.artifact-root-ready=true\n' \
  >"$preflight_artifact_root/.artifact-root-ready.labels"
MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$preflight_artifact_root" docker compose \
  --env-file "$repository_root/deployment/.env.example" \
  -f "$repository_root/deployment/docker-compose.yml" \
  -f "$repository_root/deployment/docker-compose.model-shadow-performance-generated.yml" \
  config --no-env-resolution --format json >"$resolved_config"
rm -rf "$preflight_artifact_root"

if MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$preflight_artifact_root" node \
  "$repository_root/scripts/check-compose-security-config.mjs" \
  model-shadow-performance --require-existing-source <"$resolved_config"; then
  echo "missing artifact root passed the startup preflight" >&2
  exit 1
fi
if [[ -e "$preflight_artifact_root" ]]; then
  echo "startup preflight created the missing artifact root" >&2
  exit 1
fi

set +e
missing_source_output="$(ARTIFACT_ROOT="$artifact_root" docker compose \
  --project-name "$project" -f "$compose_file" \
  up --abort-on-container-exit --exit-code-from reader reader 2>&1)"
missing_source_status=$?
set -e
if [[ $missing_source_status -eq 0 ]]; then
  echo "Docker Compose started a service with a missing protected bind source" >&2
  exit 1
fi
if [[ -e "$artifact_root" ]]; then
  echo "Docker Compose implicitly created the missing protected bind source" >&2
  printf '%s\n' "$missing_source_output" >&2
  exit 1
fi
echo "Direct missing-source startup exit status: $missing_source_status"
echo "Missing bind source remained absent."

mkdir "$artifact_root"
printf 'verified\n' >"$artifact_root/probe"
printf 'com.frauddetection.artifact-root-ready=true\n' >"$artifact_root/.artifact-root-ready.labels"
MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$artifact_root" docker compose \
  --env-file "$repository_root/deployment/.env.example" \
  -f "$repository_root/deployment/docker-compose.yml" \
  -f "$repository_root/deployment/docker-compose.model-shadow-performance-generated.yml" \
  config --no-env-resolution --format json >"$resolved_config"
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
if find "$artifact_root" -mindepth 1 -maxdepth 1 ! -name probe ! -name .artifact-root-ready.labels -print -quit | grep -q .; then
  echo "read-only artifact mount added an unexpected host artifact" >&2
  exit 1
fi

echo "Compose bind-mount security behavior passed."
