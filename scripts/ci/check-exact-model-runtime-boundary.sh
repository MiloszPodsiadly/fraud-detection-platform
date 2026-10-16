#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project="exact-model-runtime-$RANDOM-$$"
workspace="$(mktemp -d)"
artifact_parent="$workspace/generated"
artifact_root="$artifact_parent/model-shadow-performance"
identity_root="$workspace/local-identity"
test_env="$workspace/compose.env"
response_body="$workspace/response.json"
fixture="$repository_root/alert-service/src/test/resources/fixtures/exact-model-shadow-performance/python-generated-v1/exact_model_shadow_performance.json"
compose=(
  docker compose --project-name "$project" --env-file "$test_env"
  -f "$repository_root/deployment/docker-compose.yml"
  -f "$repository_root/deployment/docker-compose.dev.yml"
  -f "$repository_root/deployment/docker-compose.hardened.yml"
  -f "$repository_root/deployment/docker-compose.model-shadow-performance-generated.yml"
)

test_compose() {
  (
    unset INTERNAL_AUTH_TOKEN INTERNAL_AUTH_TOKEN_SHA256 INTERNAL_AUTH_JWT_SECRET
    unset TRUST_AUTHORITY_HMAC_SECRET GRAFANA_ADMIN_USER GRAFANA_ADMIN_PASSWORD
    unset KEYCLOAK_ADMIN_USER KEYCLOAK_ADMIN_PASSWORD OLLAMA_MODEL OLLAMA_KEEP_ALIVE
    unset AUTO_REPLAY_ENABLED AUTO_REPLAY_SOURCE_TYPE AUTO_REPLAY_MAX_EVENTS
    unset AUTO_REPLAY_THROTTLE_MILLIS AUTO_REPLAY_START_DELAY_MILLIS
    unset OUTBOX_PUBLISHER_ENABLED OUTBOX_RECOVERY_ENABLED OUTBOX_LEASE_DURATION
    unset OUTBOX_MAX_ATTEMPTS OUTBOX_STALE_THRESHOLD OUTBOX_PUBLISHER_DELAY_MS
    unset OUTBOX_RECOVERY_STALE_PROCESSING_THRESHOLD
    unset AUDIT_EXTERNAL_ANCHORING_OBJECT_STORE_STARTUP_CHECK_ENABLED
    MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$artifact_root" "${compose[@]}" "$@"
  )
}

cleanup() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT
  set +e
  test_compose down -v --remove-orphans >/dev/null 2>&1
  [[ $? -eq 0 ]] || cleanup_status=1
  rm -rf -- "$workspace"
  [[ $? -eq 0 ]] || cleanup_status=1
  if [[ $original_status -ne 0 ]]; then
    exit "$original_status"
  fi
  if [[ $cleanup_status -ne 0 ]]; then
    echo "Exact-model runtime boundary cleanup failed." >&2
    exit "$cleanup_status"
  fi
}
trap cleanup EXIT

test -f "$fixture"
test -f "$repository_root/deployment/.env.example"
cp "$repository_root/deployment/.env.example" "$test_env"
LOCAL_IDENTITY_FIXTURE_ROOT="$identity_root" LOCAL_GENERATED_FIXTURE_ROOT="$artifact_parent" \
  bash "$repository_root/scripts/bootstrap-local-fixtures.sh" --ensure
mkdir -p "$artifact_parent"

selection_key="$(
  cd "$repository_root/ml-inference-service"
  PYTHONPATH=. python3 - "$fixture" "$artifact_root" "$artifact_parent" <<'PY'
import json
import sys
from pathlib import Path

from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.publisher import (
    publish_exact_model_shadow_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.reader_access import (
    prepare_artifact_root,
    verify_prepared_artifact_root,
)

fixture = Path(sys.argv[1])
root = Path(sys.argv[2])
parent = Path(sys.argv[3])
prepare_artifact_root(root, parent, reader_uid=10001)
paths = publish_exact_model_shadow_artifact_set(json.loads(fixture.read_text(encoding="utf-8")), root)
verify_prepared_artifact_root(root, parent, reader_uid=10001)
print(paths["directory"].name)
PY
)"
test -n "$selection_key"

test_compose up --build -d --wait --wait-timeout 300 alert-service

if [[ "${EXACT_MODEL_RUNTIME_INJECT_FAILURE:-false}" == "true" ]]; then
  echo "Injected exact-model runtime boundary failure." >&2
  exit 96
fi

test "$(test_compose exec -T alert-service id -u)" = "10001"
test "$(test_compose exec -T alert-service id -g)" = "10001"
test_compose exec -T alert-service sh -ec \
  "test -r '/run/model-shadow-performance/$selection_key/exact_model_shadow_performance.json'; ! touch '/run/model-shadow-performance/$selection_key/runtime-write-must-fail'"

endpoint="http://127.0.0.1:8085/api/v1/governance/model-shadow-performance"
selector=(
  --get "$endpoint"
  --data-urlencode "modelName=python-logistic-fraud-model"
  --data-urlencode "modelVersion=2026-10-15.shadow"
  --data-urlencode "featureContractVersion=feature-contract-v2"
  --data-urlencode "modelArtifactSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  --data-urlencode "fromInclusive=2026-06-01T00:00:00Z"
  --data-urlencode "toInclusive=2026-06-09T23:59:59Z"
)

status="$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' "${selector[@]}")"
test "$status" = "401"
status="$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  -H 'X-Demo-User-Id: reader-without-authority' "${selector[@]}")"
test "$status" = "403"
status="$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  -H 'X-Demo-User-Id: exact-reader' -H 'X-Demo-Authorities: model-shadow-performance:read' "${selector[@]}")"
test "$status" = "200"
grep -Fq '"reportType":"EXACT_MODEL_SHADOW_PERFORMANCE_V1"' "$response_body"

status="$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  -H 'X-Demo-User-Id: exact-reader' -H 'X-Demo-Authorities: model-shadow-performance:read' \
  --get "$endpoint" --data-urlencode 'modelName=python-logistic-fraud-model' \
  --data-urlencode 'modelVersion=missing' --data-urlencode 'featureContractVersion=feature-contract-v2' \
  --data-urlencode 'modelArtifactSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')"
test "$status" = "404"

status="$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  -H 'X-Demo-User-Id: exact-reader' -H 'X-Demo-Authorities: model-shadow-performance:read' \
  --get "$endpoint" --data-urlencode 'modelName=unsafe/name' --data-urlencode 'modelVersion=version' \
  --data-urlencode 'featureContractVersion=feature-contract-v2' \
  --data-urlencode 'modelArtifactSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')"
test "$status" = "400"

printf '\n' >>"$artifact_root/$selection_key/exact_model_shadow_performance.json"
status="$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  -H 'X-Demo-User-Id: exact-reader' -H 'X-Demo-Authorities: model-shadow-performance:read' "${selector[@]}")"
test "$status" = "503"

echo "Exact-model non-root runtime boundary verified."
