#!/usr/bin/env bash
set -euo pipefail

variant="${1:-dev}"
case "$variant" in
  dev|app-hardening|full-security-hardened) ;;
  *)
    echo "Usage: $0 <dev|app-hardening|full-security-hardened>" >&2
    exit 2
    ;;
esac

project="fdp86-${variant}-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
compose=(docker compose -p "$project" --env-file deployment/.env -f deployment/docker-compose.yml -f deployment/docker-compose.dev.yml)
if [[ "$variant" == "app-hardening" ]]; then
  compose+=(-f deployment/docker-compose.hardened.yml)
elif [[ "$variant" == "full-security-hardened" ]]; then
  compose+=(
    -f deployment/docker-compose.oidc.yml
    -f deployment/docker-compose.service-identity-mtls.yml
    -f deployment/docker-compose.trust-authority-jwt.yml
    -f deployment/docker-compose.hardened.yml
  )
fi

services=(mongodb redis kafka ml-inference-service audit-trust-authority transaction-ingest-service alert-service analyst-console-ui)
health_services=(ml-inference-service audit-trust-authority transaction-ingest-service alert-service analyst-console-ui)
if [[ "$variant" == "app-hardening" ]]; then
  services+=(transaction-simulator-service)
  health_services+=(transaction-simulator-service)
elif [[ "$variant" == "full-security-hardened" ]]; then
  services=(mongodb redis kafka keycloak ml-inference-service audit-trust-authority transaction-ingest-service fraud-scoring-service alert-service analyst-console-ui)
  health_services=(ml-inference-service audit-trust-authority transaction-ingest-service fraud-scoring-service alert-service analyst-console-ui)
fi

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
  "${compose[@]}" down -v --remove-orphans || true
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
  )
  for fixture_file in "${fixture_files[@]}"; do
    if [[ ! -f "$fixture_file" ]]; then
      echo "Required generated fixture is missing: $fixture_file" >&2
      exit 1
    fi
    echo "Required generated fixture exists: $fixture_file"
  done
fi

configured_services="$("${compose[@]}" config --services)"
if grep -Fxq -e ollama -e ollama-model-init <<<"$configured_services"; then
  echo "Runtime smoke variant $variant must not include Ollama services." >&2
  printf '%s\n' "$configured_services" >&2
  exit 1
fi

"${compose[@]}" up --build -d "${services[@]}"

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
