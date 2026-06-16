# Promotion Review Readiness Read API

Status: FDP-112 implemented read-only API boundary.

FDP-112 exposes the diagnostic Promotion Review Readiness Report as an authorized read-only API. It does not generate reports, approve promotion, recommend thresholds, mutate state, change scoring, authorize payments, trigger workflow, run schedulers, or emit Kafka events.

## Endpoint

`GET /api/v1/governance/promotion-review-readiness/current`

The endpoint returns a bounded, validated `PromotionReviewReadinessReport` DTO from an already-generated FDP-111 artifact. It requires the explicit `promotion-readiness:read` authority.

## Runtime Source

The runtime provider reads only the configured current JSON artifact:

```yaml
promotion-review-readiness:
  current:
    enabled: false
    base-dir: /run/promotion-readiness
    path: ""
    max-size-bytes: 262144
```

The provider is disabled by default and has no static, sample, demo, stale, or zero fallback. It does not invoke Python, Makefile, shell commands, FDP-111 generation, Kafka, schedulers, scoring, model registry writes, or alert-service runtime mutation.

## HTTP Semantics

- `200` means a configured report exists, was parsed, and passed contract validation.
- `401` means the request is unauthenticated.
- `403` means the authenticated principal lacks `promotion-readiness:read`.
- `404` means the provider is disabled or no current report path is configured.
- `503` means the provider is configured but broken: missing file, unreadable file, malformed JSON, invalid schema, unsupported report type/version, unsupported readiness status, missing required booleans, non-JSON source, directory source, path traversal, symlink source, symlink directory, or file larger than the configured bound.

Configured-but-broken sources return `503`, not `404`, so local and operational misconfiguration remains visible.

## Validation Boundary

The Java validator validates the public report contract only. It does not recompute readiness, metrics, checks, thresholds, promotion status, production decisioning approval, payment authorization, automatic approve/decline/block logic, or analyst recommendation logic.

The response intentionally exposes only bounded diagnostic fields, including explicit non-goal booleans such as `notAnalystRecommendation`. It never exposes raw FDP-102 records, raw model cards, raw evaluation reports, transaction references, customer identifiers, feature vectors, model registry data, secrets, stack traces, or filesystem paths.

## Non-Goals

FDP-112 is not a dashboard, not a workflow, not promotion approval, not threshold recommendation, not production decisioning, not payment authorization, not automatic decisioning, not analyst recommendation logic, not a scheduler, and not a Kafka-triggered generation path.
