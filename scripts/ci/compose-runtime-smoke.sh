#!/usr/bin/env bash
set -euo pipefail

# Git Bash rewrites absolute Linux container paths passed to docker.exe unless disabled.
export MSYS_NO_PATHCONV=1

variant="${1:-dev}"
case "$variant" in
  dev|app-hardening|full-security-hardened) ;;
  *)
    echo "Usage: $0 <dev|app-hardening|full-security-hardened>" >&2
    exit 2
    ;;
esac

project="fdp86-${variant}-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}-${COMPOSE_SMOKE_ID:-$$}"
compose=(docker compose -p "$project" --env-file deployment/.env -f deployment/docker-compose.yml -f deployment/docker-compose.dev.yml)
if [[ "$variant" == "app-hardening" ]]; then
  compose+=(-f deployment/docker-compose.hardened.yml)
elif [[ "$variant" == "full-security-hardened" ]]; then
  compose+=(
    -f deployment/docker-compose.oidc.yml
    -f deployment/docker-compose.service-identity-mtls.yml
    -f deployment/docker-compose.trust-authority-jwt.yml
    -f deployment/docker-compose.hardened.yml
    -f deployment/docker-compose.model-promotion-policy-trust.yml
  )
fi

services=(mongodb redis kafka ml-inference-service audit-trust-authority transaction-ingest-service feature-enricher-service alert-service analyst-console-ui)
health_services=(ml-inference-service audit-trust-authority transaction-ingest-service feature-enricher-service alert-service analyst-console-ui)
if [[ "$variant" == "app-hardening" ]]; then
  services+=(transaction-simulator-service)
  health_services+=(transaction-simulator-service)
elif [[ "$variant" == "full-security-hardened" ]]; then
  services=(mongodb redis kafka keycloak ml-inference-service audit-trust-key-loader audit-trust-authority transaction-ingest-service feature-enricher-service fraud-scoring-service alert-service analyst-console-ui model-promotion-observation-key-loader model-promotion-policy-key-loader model-promotion-policy-publisher)
  health_services=(ml-inference-service audit-trust-authority transaction-ingest-service feature-enricher-service fraud-scoring-service alert-service analyst-console-ui)
fi

require_runtime_health_coverage() {
  local required_service="$1"
  if [[ ! " ${services[*]} " =~ " ${required_service} " ]]; then
    echo "Runtime smoke variant $variant must start $required_service." >&2
    exit 1
  fi
  if [[ ! " ${health_services[*]} " =~ " ${required_service} " ]]; then
    echo "Runtime smoke variant $variant must verify $required_service health." >&2
    exit 1
  fi
}

require_runtime_health_coverage feature-enricher-service

failed_service=""

print_service_logs() {
  for service in "${services[@]}"; do
    echo "Last 200 log lines for $service:" >&2
    "${compose[@]}" logs --tail=200 "$service" || true
  done
}

cleanup() {
  local status=$?
  if [[ "$status" -ne 0 ]]; then
    echo "Configured services for failed $variant smoke:" >&2
    "${compose[@]}" config --services || true
    echo "Container state for failed $variant smoke:" >&2
    "${compose[@]}" ps || true
    print_service_logs
    if [[ -n "$failed_service" ]]; then
      container_id="$("${compose[@]}" ps -q "$failed_service" 2>/dev/null || true)"
      if [[ -n "$container_id" ]]; then
        echo "Container inspect for failed service $failed_service:" >&2
        docker inspect "$container_id" || true
      fi
    fi
  else
    "${compose[@]}" ps || true
  fi
  "${compose[@]}" down --remove-orphans || true
  exit "$status"
}
trap cleanup EXIT

if [[ "$variant" == "full-security-hardened" ]]; then
  fixture_files=(
    deployment/.local/service-identity/mtls/local-dev-ca.pem
    deployment/.local/service-identity/mtls/ml-inference-service.pem
    deployment/.local/service-identity/mtls/ml-inference-service-key.pem
    deployment/.local/service-identity/alert-service-private.pem
    deployment/.local/service-identity/jwks.json
    deployment/.local/audit-trust-authority/ed25519-private.key
    deployment/.local/audit-trust-authority/ed25519-public.key
    deployment/.local/model-promotion-policy/trusted-issuers.json
    deployment/.local/model-promotion-policy/private-key.pkcs8.base64
    deployment/.local/model-promotion-policy/approval/publisher.private-key.pkcs8.base64
    deployment/.local/model-promotion-policy/fleet-observation/alert.private-key.pkcs8.base64
  )
  for fixture_file in "${fixture_files[@]}"; do
    if [[ ! -f "$fixture_file" ]]; then
      echo "Required generated fixture is missing: $fixture_file" >&2
      exit 1
    fi
    echo "Required generated fixture exists: $fixture_file"
  done
fi

if [[ "$variant" == "full-security-hardened" ]]; then
  "${compose[@]}" config --format json | node scripts/check-compose-security-config.mjs security-hardened
fi

configured_services="$("${compose[@]}" config --services)"
if grep -Fxq -e ollama -e ollama-model-init <<<"$configured_services"; then
  echo "Runtime smoke variant $variant must not include Ollama services." >&2
  printf '%s\n' "$configured_services" >&2
  exit 1
fi

# Hosted runners can terminate npm or Maven build steps when Compose builds every image concurrently.
# Keep runtime smoke deterministic by default while allowing an explicit faster local override.
compose_parallel_limit="${COMPOSE_PARALLEL_LIMIT:-1}"
echo "Using Docker Compose parallel limit for runtime smoke: $compose_parallel_limit"
"${compose[@]}" --parallel "$compose_parallel_limit" up --build -d "${services[@]}"

for service in "${health_services[@]}"; do
  healthy=false
  for attempt in {1..60}; do
    container_id="$("${compose[@]}" ps -q "$service")"
    if [[ -n "$container_id" ]]; then
      state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
      echo "Waiting for $service health: state=$state attempt=$attempt/60"
      if [[ "$state" == "healthy" ]]; then
        healthy=true
        break
      fi
      if [[ "$state" == "unhealthy" || "$state" == "exited" || "$state" == "dead" ]]; then
        failed_service="$service"
        echo "$service entered terminal state: $state" >&2
        exit 1
      fi
    else
      echo "Waiting for $service container: attempt=$attempt/60"
    fi
    sleep 5
  done
  if [[ "$healthy" != "true" ]]; then
    failed_service="$service"
    echo "$service did not become healthy within 300 seconds" >&2
    "${compose[@]}" logs --tail=200 "$service" || true
    exit 1
  fi
done

if [[ "$variant" == "full-security-hardened" ]]; then
  for loader in audit-trust-key-loader model-promotion-observation-key-loader model-promotion-policy-key-loader; do
    loader_id="$("${compose[@]}" ps -a -q "$loader")"
    if [[ -z "$loader_id" || "$(docker inspect --format '{{.State.Status}}:{{.State.ExitCode}}' "$loader_id")" != "exited:0" ]]; then
      failed_service="$loader"
      echo "$loader did not complete successfully." >&2
      exit 1
    fi
  done

  authority_uid="$("${compose[@]}" exec -T audit-trust-authority id -u)"
  if [[ "$authority_uid" != "10001" ]]; then
    failed_service="audit-trust-authority"
    echo "Audit trust authority must run as UID 10001, got $authority_uid" >&2
    exit 1
  fi
  for key in ed25519-private.key ed25519-public.key; do
    key_path="/run/audit-trust-keys/$key"
    if ! "${compose[@]}" exec -T audit-trust-authority test -r "$key_path"; then
      failed_service="audit-trust-authority"
      echo "Non-root audit trust authority cannot read its $key." >&2
      exit 1
    fi
    if [[ "$("${compose[@]}" exec -T audit-trust-authority stat -c '%u:%g:%a' "$key_path")" != "10001:10001:400" ]]; then
      failed_service="audit-trust-authority"
      echo "Audit trust authority $key has incorrect owner or mode." >&2
      exit 1
    fi
    if "${compose[@]}" exec -T alert-service test -e "$key_path"; then
      failed_service="alert-service"
      echo "Audit trust authority $key is visible to alert-service." >&2
      exit 1
    fi
  done
  if "${compose[@]}" exec -T audit-trust-authority test -e /run/policy-observation/alert.private-key.pkcs8.base64; then
    failed_service="audit-trust-authority"
    echo "Alert observation key is visible to audit trust authority." >&2
    exit 1
  fi

  publisher_id="$("${compose[@]}" ps -q model-promotion-policy-publisher)"
  if [[ -z "$publisher_id" || "$(docker inspect --format '{{.State.Status}}' "$publisher_id")" != "running" ]]; then
    failed_service="model-promotion-policy-publisher"
    echo "Policy publisher is not running after key loading." >&2
    exit 1
  fi
  publisher_uid="$("${compose[@]}" exec -T model-promotion-policy-publisher id -u)"
  if [[ "$publisher_uid" != "10001" ]]; then
    failed_service="model-promotion-policy-publisher"
    echo "Policy publisher must run as UID 10001, got $publisher_uid" >&2
    exit 1
  fi
  for key in publisher issuer; do
    key_path="/run/policy-publication/${key}.private-key.pkcs8.base64"
    if ! "${compose[@]}" exec -T model-promotion-policy-publisher test -r "$key_path"; then
      failed_service="model-promotion-policy-publisher"
      echo "Non-root publisher cannot read its $key signing material." >&2
      exit 1
    fi
    if [[ "$("${compose[@]}" exec -T model-promotion-policy-publisher stat -c '%u:%g:%a' "$key_path")" != "10001:10001:400" ]]; then
      failed_service="model-promotion-policy-publisher"
      echo "Publisher $key signing material has incorrect owner or mode." >&2
      exit 1
    fi
    if "${compose[@]}" exec -T alert-service test -e "$key_path"; then
      failed_service="alert-service"
      echo "Publisher $key signing material is visible to alert-service." >&2
      exit 1
    fi
  done
  reader_uid="$("${compose[@]}" exec -T alert-service id -u)"
  if [[ "$reader_uid" != "10001" ]]; then
    failed_service="alert-service"
    echo "alert-service trust reader must run as UID 10001, got $reader_uid" >&2
    exit 1
  fi
  if ! "${compose[@]}" exec -T alert-service test -r /run/model-promotion-policy/trusted-issuers.json; then
    failed_service="alert-service"
    echo "Non-root alert-service cannot read the public policy trust registry." >&2
    exit 1
  fi
  if "${compose[@]}" exec -T alert-service test -e /run/model-promotion-policy/private-key.pkcs8.base64; then
    failed_service="alert-service"
    echo "Policy issuer private key is visible to alert-service." >&2
    exit 1
  fi
  observation_key=/run/policy-observation/alert.private-key.pkcs8.base64
  if ! "${compose[@]}" exec -T alert-service test -r "$observation_key"; then
    failed_service="alert-service"
    echo "Non-root alert-service cannot read its observation verification key." >&2
    exit 1
  fi
  if [[ "$("${compose[@]}" exec -T alert-service stat -c '%u:%g:%a' "$observation_key")" != "10001:10001:400" ]]; then
    failed_service="alert-service"
    echo "Alert observation key has incorrect owner or mode." >&2
    exit 1
  fi
  if "${compose[@]}" exec -T model-promotion-policy-publisher test -e "$observation_key"; then
    failed_service="model-promotion-policy-publisher"
    echo "Alert observation key is visible to model-promotion policy publisher." >&2
    exit 1
  fi
  if "${compose[@]}" exec -T audit-trust-authority test -w /run/audit-trust-keys/ed25519-private.key; then
    failed_service="audit-trust-authority"
    echo "Read-only audit trust key volume is writable by the authority." >&2
    exit 1
  fi
  if ! node scripts/ci/verify-audit-trust-runtime.mjs "$project"; then
    failed_service="audit-trust-authority"
    exit 1
  fi
fi

echo "Verifying analyst-console-ui endpoint: http://127.0.0.1:4173/"
if ! curl -fsS http://127.0.0.1:4173/ >/dev/null; then
  failed_service="analyst-console-ui"
  exit 1
fi
echo "Verifying alert-service readiness endpoint: http://127.0.0.1:8085/actuator/health/readiness"
if ! curl -fsS http://127.0.0.1:8085/actuator/health/readiness >/dev/null; then
  failed_service="alert-service"
  exit 1
fi
if [[ "$variant" == "full-security-hardened" ]]; then
  echo "Verifying ML HTTPS endpoint with generated local CA: https://localhost:8090/health"
  ml_curl_args=(-fsS --cacert deployment/.local/service-identity/mtls/local-dev-ca.pem)
  if curl --version | grep -q Schannel; then
    echo "Using Schannel local-CA verification without revocation lookup for generated fixtures."
    ml_curl_args+=(--ssl-no-revoke)
  fi
  if ! curl "${ml_curl_args[@]}" https://localhost:8090/health >/dev/null; then
    failed_service="ml-inference-service"
    exit 1
  fi
else
  echo "Verifying ML HTTP endpoint: http://127.0.0.1:8090/health"
  if ! curl -fsS http://127.0.0.1:8090/health >/dev/null; then
    failed_service="ml-inference-service"
    exit 1
  fi
fi
