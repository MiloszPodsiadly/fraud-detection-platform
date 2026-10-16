#!/usr/bin/env bash
set -euo pipefail

docker version --format 'Docker Engine: {{.Server.Version}}'
docker compose version

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workspace="$(mktemp -d)"
project="exact-model-bind-$RANDOM-$$"
compose_file="$workspace/compose.yml"
create_path_compose_file="$workspace/compose-create-path.yml"
artifact_root="$workspace/artifacts"
mutation_root="$workspace/create-path-mutation"
preflight_artifact_root="$workspace/preflight-artifacts"
malformed_sentinel_root="$workspace/malformed-sentinel-artifacts"
resolved_config="$workspace/resolved.json"
probe_image="alpine:3.21"

cleanup() {
  docker compose --project-name "$project" -f "$compose_file" down --remove-orphans >/dev/null 2>&1 || true
  docker compose --project-name "$project-mutation" -f "$create_path_compose_file" down --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$workspace"
}
trap cleanup EXIT

if ! docker image inspect "$probe_image" >/dev/null 2>&1; then
  echo "Pulling the bind-mount probe image before the negative case."
  docker pull "$probe_image"
fi
docker image inspect "$probe_image" >/dev/null

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

cat >"$create_path_compose_file" <<'YAML'
services:
  reader:
    image: alpine:3.21
    command: ["/bin/sh", "-ec", "test -d /run/model-shadow-performance"]
    volumes:
      - type: bind
        source: ${ARTIFACT_ROOT:?ARTIFACT_ROOT is required}
        target: /run/model-shadow-performance
        read_only: true
        bind:
          create_host_path: true
YAML

# Proof A: the repository-owned preflight rejects missing, malformed and unsafe roots.
mkdir "$preflight_artifact_root"
printf 'com.frauddetection.artifact-root-ready=true\n' \
  >"$preflight_artifact_root/.artifact-root-ready.labels"
MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$preflight_artifact_root" docker compose \
  --env-file "$repository_root/deployment/.env.example" \
  -f "$repository_root/deployment/docker-compose.yml" \
  -f "$repository_root/deployment/docker-compose.model-shadow-performance-generated.yml" \
  config --no-env-resolution --format json >"$resolved_config"
rm -rf "$preflight_artifact_root"

if node "$repository_root/scripts/check-compose-security-config.mjs" \
  model-shadow-performance --require-existing-source <"$resolved_config"; then
  echo "missing artifact root passed the application preflight" >&2
  exit 1
fi
[[ ! -e "$preflight_artifact_root" ]] || {
  echo "application preflight created the missing artifact root" >&2
  exit 1
}

mkdir "$malformed_sentinel_root"
printf 'com.frauddetection.artifact-root-ready=false\n' \
  >"$malformed_sentinel_root/.artifact-root-ready.labels"
MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$malformed_sentinel_root" docker compose \
  --env-file "$repository_root/deployment/.env.example" \
  -f "$repository_root/deployment/docker-compose.yml" \
  -f "$repository_root/deployment/docker-compose.model-shadow-performance-generated.yml" \
  config --no-env-resolution --format json >"$resolved_config"
if node "$repository_root/scripts/check-compose-security-config.mjs" \
  model-shadow-performance --require-existing-source <"$resolved_config"; then
  echo "malformed artifact-root readiness sentinel passed the application preflight" >&2
  exit 1
fi
rm -rf "$malformed_sentinel_root"

mkdir "$preflight_artifact_root"
if MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$preflight_artifact_root" docker compose \
  --env-file "$repository_root/deployment/.env.example" \
  -f "$repository_root/deployment/docker-compose.yml" \
  -f "$repository_root/deployment/docker-compose.model-shadow-performance-generated.yml" \
  config --no-env-resolution --format json >"$resolved_config" 2>"$workspace/missing-sentinel.err"; then
  echo "production overlay accepted a missing readiness sentinel" >&2
  exit 1
fi
rm -rf "$preflight_artifact_root"

# Proof B: no label_file or application checker participates in this Docker bind-source probe.
set +e
missing_source_output="$(ARTIFACT_ROOT="$artifact_root" docker compose \
  --project-name "$project" -f "$compose_file" create reader 2>&1)"
missing_source_status=$?
set -e
if [[ $missing_source_status -eq 0 ]]; then
  echo "Docker Compose accepted create_host_path:false with a missing bind source" >&2
  exit 1
fi
if [[ -e "$artifact_root" ]]; then
  echo "Docker Compose implicitly created the missing protected bind source" >&2
  printf '%s\n' "$missing_source_output" >&2
  exit 1
fi
if ! printf '%s' "$missing_source_output" | grep -Eiq 'bind[^[:cntrl:]]*(source|mount)[^[:cntrl:]]*(does not exist|not found)|source path[^[:cntrl:]]*(does not exist|not found)'; then
  echo "Docker Compose failed, but not with a verified missing bind-source diagnostic" >&2
  printf '%s\n' "$missing_source_output" >&2
  exit 1
fi
if [[ -n "$(docker compose --project-name "$project" -f "$compose_file" ps --status running -q reader)" ]]; then
  echo "missing-source probe unexpectedly left a running container" >&2
  exit 1
fi
echo "Docker missing-bind refusal exit status: $missing_source_status"
printf 'Docker missing-bind diagnostic: %s\n' "$(printf '%s' "$missing_source_output" | tail -n 1)"

ARTIFACT_ROOT="$mutation_root" docker compose --project-name "$project-mutation" \
  -f "$create_path_compose_file" up --abort-on-container-exit --exit-code-from reader reader
[[ -d "$mutation_root" ]] || {
  echo "create_host_path:true control did not create its disposable source" >&2
  exit 1
}
docker compose --project-name "$project-mutation" -f "$create_path_compose_file" down --remove-orphans
rm -rf "$mutation_root"

# Proof C: an existing bind is readable but cannot be modified by the container.
mkdir "$artifact_root"
printf 'verified\n' >"$artifact_root/probe"
probe_sha256="$(sha256sum "$artifact_root/probe" | awk '{print $1}')"
ARTIFACT_ROOT="$artifact_root" docker compose --project-name "$project" -f "$compose_file" \
  up --abort-on-container-exit --exit-code-from reader reader

[[ ! -e "$artifact_root/must-not-write" ]] || {
  echo "read-only artifact mount changed the host artifact root" >&2
  exit 1
}
if [[ "$(sha256sum "$artifact_root/probe" | awk '{print $1}')" != "$probe_sha256" ]]; then
  echo "read-only artifact mount modified the host probe" >&2
  exit 1
fi
if find "$artifact_root" -mindepth 1 -maxdepth 1 ! -name probe -print -quit | grep -q .; then
  echo "read-only artifact mount added an unexpected host artifact" >&2
  exit 1
fi

echo "Application preflight, Docker bind refusal and read-only enforcement passed independently."
