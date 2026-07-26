# Promotion Review Readiness Read API

Status: FDP-112 implemented read-only API boundary.

FDP-112 exposes the diagnostic Promotion Review Readiness Report as an authorized read-only API. It does not generate reports, approve promotion, recommend thresholds, mutate state, change scoring, authorize payments, trigger workflow, run schedulers, or emit Kafka events.

## Endpoint

`GET /api/v1/governance/promotion-review-readiness/current`

The endpoint returns a bounded, validated `PromotionReviewReadinessReport` DTO from an already-generated FDP-111 artifact. It requires the explicit `promotion-readiness:read` authority.

## Runtime Source

The runtime provider reads only the configured current report artifact and its required sibling `manifest.json`:

```yaml
promotion-review-readiness:
  current:
    enabled: false
    base-dir: /run/promotion-readiness
    path: ""
    max-size-bytes: 262144
```

The provider is disabled by default and has no static, sample, demo, stale, or zero fallback. It does not invoke Python, Makefile, shell commands, FDP-111 generation, Kafka, schedulers, scoring, model registry writes, or alert-service runtime mutation.
The configured path must resolve to `promotion-review-readiness-report.json`; the provider rejects standalone reports
without `manifest.json`, renamed reports, malformed manifests, hash mismatches, size mismatches, and manifest/report
`generatedAt` mismatches.

## HTTP Semantics

- `200` means a configured report exists, was parsed, and passed contract validation.
- `401` means the request is unauthenticated.
- `403` means the authenticated principal lacks `promotion-readiness:read`.
- `404` means the provider is disabled or no current report path is configured.
- `503` means the provider is configured but broken: missing file, missing manifest, unreadable file, malformed JSON, duplicate JSON key, invalid schema, unsupported report type/version, unsupported readiness status, missing required booleans, non-JSON source, directory source, path traversal, symlink source, symlink directory, hash mismatch, size mismatch, generatedAt mismatch, or file larger than the configured bound.

Configured-but-broken sources return `503`, not `404`, so local and operational misconfiguration remains visible.

## Validation Boundary

The Java validator validates the public report contract and recomputes readiness from immutable `checkInputs`. It does not recompute metrics, thresholds, promotion status, production decisioning approval, payment authorization, automatic approve/decline/block logic, or analyst recommendation logic.
The manifest SHA-256 binds the manifest to exact local report bytes. It is not a signature, producer identity proof,
cryptographic attestation, or protection from a privileged writer replacing both files inside the deployment trust
boundary.

The response intentionally exposes only bounded diagnostic fields, including explicit non-goal booleans such as `notAnalystRecommendation`. It never exposes raw FDP-102 records, raw evaluation cards, raw evaluation reports, transaction references, customer identifiers, feature vectors, model registry data, secrets, stack traces, or filesystem paths.

## Non-Goals

FDP-112 is not a dashboard, not a workflow, not promotion approval, not threshold recommendation, not production decisioning, not payment authorization, not automatic decisioning, not analyst recommendation logic, not a scheduler, and not a Kafka-triggered generation path.
