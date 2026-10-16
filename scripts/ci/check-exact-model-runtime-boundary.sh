#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project="exact-model-runtime-$RANDOM-$$"
workspace="$(mktemp -d)"
artifact_parent="$workspace/generated"
artifact_root="$artifact_parent/model-shadow-performance"
response_body="$workspace/response.json"
fixture="$repository_root/alert-service/src/test/resources/fixtures/exact-model-shadow-performance/python-generated-v1/exact_model_shadow_performance.json"
compose=(
  docker compose --project-name "$project" --env-file "$repository_root/deployment/.env"
  -f "$repository_root/deployment/docker-compose.yml"
  -f "$repository_root/deployment/docker-compose.dev.yml"
  -f "$repository_root/deployment/docker-compose.hardened.yml"
  -f "$repository_root/deployment/docker-compose.model-shadow-performance-generated.yml"
)

cleanup() {
  MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$artifact_root" "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$workspace"
}
trap cleanup EXIT

test -f "$fixture"
test -f "$repository_root/deployment/.env.example"
cp "$repository_root/deployment/.env.example" "$repository_root/deployment/.env"
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

MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$artifact_root" "${compose[@]}" up --build -d --wait --wait-timeout 300 alert-service

test "$(MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$artifact_root" "${compose[@]}" exec -T alert-service id -u)" = "10001"
test "$(MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$artifact_root" "${compose[@]}" exec -T alert-service id -g)" = "10001"
MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT="$artifact_root" "${compose[@]}" exec -T alert-service sh -ec \
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
