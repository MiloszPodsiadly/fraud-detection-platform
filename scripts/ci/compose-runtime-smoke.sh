#!/usr/bin/env bash
set -euo pipefail

variant="${1:-dev}"
case "$variant" in
  dev|app-hardening) ;;
  *)
    echo "Usage: $0 <dev|app-hardening>" >&2
    exit 2
    ;;
esac

project="fdp86-${variant}-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
compose=(docker compose -p "$project" --env-file deployment/.env -f deployment/docker-compose.yml -f deployment/docker-compose.dev.yml)
if [[ "$variant" == "app-hardening" ]]; then
  compose+=(-f deployment/docker-compose.hardened.yml)
fi

services=(mongodb redis kafka ml-inference-service audit-trust-authority transaction-ingest-service alert-service analyst-console-ui)
health_services=(ml-inference-service audit-trust-authority transaction-ingest-service alert-service analyst-console-ui)
if [[ "$variant" == "app-hardening" ]]; then
  services+=(transaction-simulator-service)
  health_services+=(transaction-simulator-service)
fi

cleanup() {
  local status=$?
  "${compose[@]}" ps || true
  if [[ "$status" -ne 0 ]]; then
    "${compose[@]}" logs --tail=200 || true
    for service in "${health_services[@]}"; do
      container_id="$("${compose[@]}" ps -q "$service" 2>/dev/null || true)"
      if [[ -n "$container_id" ]]; then
        docker inspect "$container_id" || true
      fi
    done
  fi
  "${compose[@]}" down -v --remove-orphans || true
  exit "$status"
}
trap cleanup EXIT

"${compose[@]}" up --build -d "${services[@]}"

for service in "${health_services[@]}"; do
  healthy=false
  for _ in {1..60}; do
    container_id="$("${compose[@]}" ps -q "$service")"
    if [[ -n "$container_id" ]]; then
      state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
      if [[ "$state" == "healthy" ]]; then
        healthy=true
        break
      fi
      if [[ "$state" == "unhealthy" || "$state" == "exited" || "$state" == "dead" ]]; then
        echo "$service entered terminal state: $state" >&2
        exit 1
      fi
    fi
    sleep 5
  done
  if [[ "$healthy" != "true" ]]; then
    echo "$service did not become healthy within 300 seconds" >&2
    exit 1
  fi
done

curl -fsS http://127.0.0.1:4173/ >/dev/null
curl -fsS http://127.0.0.1:8085/actuator/health/readiness >/dev/null
curl -fsS http://127.0.0.1:8090/health >/dev/null
