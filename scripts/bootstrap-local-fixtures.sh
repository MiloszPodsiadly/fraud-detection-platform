#!/usr/bin/env bash
set -euo pipefail

if ! command -v openssl >/dev/null 2>&1; then
  echo "OpenSSL is required to generate local identity fixtures. Install OpenSSL and rerun this command." >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_root="$repo_root/deployment/.local"
output_dir="$local_root/service-identity"

mkdir -p "$local_root"
staging_dir="$(mktemp -d "$local_root/service-identity.tmp.XXXXXX")"
mtls_dir="$staging_dir/mtls"
trap 'rm -rf "$staging_dir"' EXIT

umask 077
mkdir -p "$mtls_dir"

cat > "$staging_dir/ca.cnf" <<'EOF'
[req]
distinguished_name = dn
prompt = no
x509_extensions = v3_ca

[dn]
CN = fraud-platform-local-dev-ca

[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,digitalSignature,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
EOF

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 365 \
  -config "$staging_dir/ca.cnf" \
  -keyout "$mtls_dir/local-dev-ca-key.pem" \
  -out "$mtls_dir/local-dev-ca.pem" >/dev/null 2>&1

create_certificate() {
  local service_name="$1"
  local usage="$2"
  local sans="$3"
  local days="$4"
  local config="$staging_dir/$service_name.cnf"

  cat > "$config" <<EOF
[req]
distinguished_name = dn
prompt = no
req_extensions = v3_req

[dn]
CN = $service_name

[v3_req]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = $usage
subjectAltName = $sans
EOF

  openssl req -new -newkey rsa:2048 -nodes -sha256 \
    -config "$config" \
    -keyout "$mtls_dir/$service_name-key.pem" \
    -out "$staging_dir/$service_name.csr" >/dev/null 2>&1
  openssl x509 -req -sha256 -days "$days" \
    -in "$staging_dir/$service_name.csr" \
    -CA "$mtls_dir/local-dev-ca.pem" \
    -CAkey "$mtls_dir/local-dev-ca-key.pem" \
    -CAcreateserial \
    -extfile "$config" \
    -extensions v3_req \
    -out "$mtls_dir/$service_name.pem" >/dev/null 2>&1
}

create_certificate "ml-inference-service" "serverAuth" \
  "DNS:ml-inference-service,DNS:localhost,IP:127.0.0.1,URI:spiffe://fraud-platform/ml-inference-service" 365
create_certificate "alert-service" "clientAuth" \
  "DNS:alert-service,URI:spiffe://fraud-platform/alert-service" 365
create_certificate "fraud-scoring-service" "clientAuth" \
  "DNS:fraud-scoring-service,URI:spiffe://fraud-platform/fraud-scoring-service" 365
create_certificate "unknown-service" "clientAuth" \
  "DNS:unknown-service,URI:spiffe://fraud-platform/unknown-service" 365
create_certificate "expired-service" "clientAuth" \
  "DNS:expired-service,URI:spiffe://fraud-platform/expired-service" 0

for service_name in fraud-scoring-service alert-service; do
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$staging_dir/$service_name-private.pem" >/dev/null 2>&1
  openssl pkey -in "$staging_dir/$service_name-private.pem" -pubout \
    -out "$staging_dir/$service_name-public.pem" >/dev/null 2>&1
done

rsa_modulus_base64url() {
  local private_key="$1"
  local hex
  local binary_file="$staging_dir/modulus.bin"
  hex="$(openssl rsa -in "$private_key" -noout -modulus 2>/dev/null)"
  hex="${hex#Modulus=}"
  : > "$binary_file"
  while [[ -n "$hex" ]]; do
    printf '%b' "\\x${hex:0:2}" >> "$binary_file"
    hex="${hex:2}"
  done
  openssl base64 -A -in "$binary_file" | tr '+/' '-_' | tr -d '='
}

scoring_modulus="$(rsa_modulus_base64url "$staging_dir/fraud-scoring-service-private.pem")"
alert_modulus="$(rsa_modulus_base64url "$staging_dir/alert-service-private.pem")"
cat > "$staging_dir/jwks.json" <<EOF
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "scoring-key-1",
      "n": "$scoring_modulus",
      "e": "AQAB"
    },
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "alert-key-1",
      "n": "$alert_modulus",
      "e": "AQAB"
    }
  ]
}
EOF

rm -f "$staging_dir"/*.cnf "$staging_dir"/*.csr "$staging_dir/modulus.bin" "$mtls_dir/local-dev-ca.srl"
chmod 755 "$staging_dir" "$mtls_dir"
chmod 444 "$staging_dir"/*.pem "$staging_dir/jwks.json" "$mtls_dir"/*.pem

rm -rf "$output_dir"
mv "$staging_dir" "$output_dir"
trap - EXIT

echo "Generated local-only identity fixtures in deployment/.local/service-identity/."
echo "This directory is ignored by Git and excluded from Docker build contexts."

generated_eval_dir="$repo_root/deployment/local-generated/platform-recommendation-evaluation-card"
generated_shadow_dir="$repo_root/deployment/local-generated/shadow-performance"

fixtures_eval_dir="$repo_root/deployment/local-fixtures/platform-recommendation-evaluation-card"
fixtures_shadow_dir="$repo_root/deployment/local-fixtures/shadow-performance"

mkdir -p "$generated_eval_dir" "$generated_shadow_dir"

if [ -d "$fixtures_shadow_dir" ]; then
  cp -r "$fixtures_shadow_dir"/* "$generated_shadow_dir/" 2>/dev/null || true
fi

if [ -d "$fixtures_eval_dir" ]; then
  cp -r "$fixtures_eval_dir"/* "$generated_eval_dir/" 2>/dev/null || true
fi

cat > "$generated_eval_dir/platform_recommendation_evaluation_card.json" <<'EOF'
{
  "cardVersion": "platform-recommendation-evaluation-card-v1",
  "cardType": "PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1",
  "generatedAt": "2026-06-12T00:00:00Z",
  "evaluationSubject": {
    "subjectType": "PLATFORM_RECOMMENDATION",
    "sourceComponent": "ENGINE_INTELLIGENCE_PROJECTION",
    "sourceVersion": "ENGINE_INTELLIGENCE_PROJECTION_V1",
    "featureContractVersion": "NOT_APPLICABLE",
    "modelIdentity": "NOT_AVAILABLE",
    "modelArtifactSha256": "NOT_AVAILABLE",
    "identityCompleteness": "NO_MODEL_ARTIFACT_IDENTITY_IN_FDP123_SOURCE"
  },
  "metricsSubject": "PLATFORM_RECOMMENDATION",
  "metricBasis": "ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK",
  "allowedUsageModes": [
    "SHADOW",
    "COMPARE",
    "OFFLINE_EVALUATION"
  ],
  "evaluationPurpose": "OFFLINE_DIAGNOSTIC",
  "runtimeDecisionAuthority": "NONE",
  "promotionAuthority": "NONE",
  "thresholdChangeAuthority": "NONE",
  "paymentAuthorizationAuthority": "NONE",
  "workflowAuthority": "NONE",
  "intendedUse": [
    "SHADOW_FRAUD_RISK_REVIEW",
    "OFFLINE_DIAGNOSTIC_ANALYSIS"
  ],
  "notIntendedUse": [
    "NO_AUTOMATIC_CUSTOMER_BLOCKING",
    "NO_AUTOMATIC_TRANSACTION_DECLINE",
    "NO_CASE_WORKFLOW_AUTOMATION",
    "NO_FINAL_BANK_DECISION",
    "NO_MODEL_PROMOTION_APPROVAL",
    "NO_PAYMENT_AUTHORIZATION",
    "NO_PRODUCTION_THRESHOLD_MUTATION",
    "NO_REGULATORY_CERTIFICATION_CLAIM"
  ],
  "evaluationEvidence": {
    "evaluationReportType": "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1",
    "evaluationGeneratedAt": "2026-06-10T00:00:00Z",
    "evaluationArtifactSetVersion": "fdp123-report-artifact-set-v1",
    "datasetVersion": "feedback-dataset-v1",
    "datasetTimeBasis": "FEEDBACK_CREATED_AT",
    "recordsEvaluated": 2,
    "positiveClassCount": 1,
    "negativeClassCount": 1,
    "warnings": [
      "LOW_SAMPLE_SIZE"
    ],
    "sourceManifestSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "metricsSummary": {
    "alertRecommendedPrecision": {
      "available": true,
      "value": 0.5,
      "reason": null
    },
    "alertRecommendedRecall": {
      "available": true,
      "value": 0.5,
      "reason": null
    },
    "falsePositiveRate": {
      "available": true,
      "value": 0.5,
      "reason": null
    },
    "falseNegativeRate": {
      "available": true,
      "value": 0.5,
      "reason": null
    }
  },
  "warnings": [
    "LOW_SAMPLE_SIZE"
  ],
  "limitations": [
    "ANALYST_FEEDBACK_LABELS_ARE_NOT_LEGAL_GROUND_TRUTH",
    "METRICS_ARE_PLATFORM_RECOMMENDATION_DIAGNOSTICS",
    "OFFLINE_DIAGNOSTIC_METRICS_ARE_NOT_PRODUCTION_APPROVAL",
    "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_APPROVE_PROMOTION",
    "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_AUTHORIZE_AUTOMATIC_DECLINE",
    "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_CHANGE_SCORING_THRESHOLDS",
    "PSEUDONYMOUS_REFERENCES_ARE_NOT_ANONYMIZATION",
    "SMALL_SAMPLE_SIZE_MAY_BE_INCONCLUSIVE"
  ],
  "governanceBoundary": [
    "NO_CASE_CREATION",
    "NO_EXTERNAL_PUBLISHING",
    "NO_FINAL_DECISIONING",
    "NO_MODEL_PROMOTION",
    "NO_PAYMENT_AUTHORIZATION",
    "NO_PRODUCTION_APPROVAL",
    "NO_THRESHOLD_RECOMMENDATION",
    "NO_WORKFLOW_AUTOMATION"
  ]
}
EOF

cat > "$generated_eval_dir/platform_recommendation_evaluation_card.md" <<'EOF'
# Platform Evaluation Card

- **Card Version:** platform-recommendation-evaluation-card-v1
- **Card Type:** PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1
- **Generated At:** 2026-06-12T00:00:00Z

## Summary
Offline diagnostic performance monitoring card.
EOF

json_file="$generated_eval_dir/platform_recommendation_evaluation_card.json"
md_file="$generated_eval_dir/platform_recommendation_evaluation_card.md"

json_size=$(wc -c < "$json_file" | tr -d ' ')
md_size=$(wc -c < "$md_file" | tr -d ' ')

json_sha=$(openssl dgst -sha256 "$json_file" | awk '{print $NF}')
md_sha=$(openssl dgst -sha256 "$md_file" | awk '{print $NF}')

cat > "$generated_eval_dir/manifest.json" <<EOF
{
  "artifactSetVersion": "platform-recommendation-evaluation-card-artifact-set-v1",
  "reportType": "PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1",
  "generatedAt": "2026-06-12T00:00:00Z",
  "files": [
    {
      "name": "platform_recommendation_evaluation_card.json",
      "sizeBytes": $json_size,
      "sha256": "$json_sha"
    },
    {
      "name": "platform_recommendation_evaluation_card.md",
      "sizeBytes": $md_size,
      "sha256": "$md_sha"
    }
  ]
}
EOF

echo "Generated local evaluation fixtures in deployment/local-generated/."