# Promotion Review Readiness Report

Status: local/offline diagnostic report artifact.

Promotion Review Readiness is a diagnostic report only. It consumes existing bounded artifacts and answers only:

```text
Do we have enough validated diagnostic material for a human promotion review to begin?
```

It does not answer:

```text
Should the model be promoted?
Should threshold change?
Should production decisioning be enabled?
Should a payment be approved or declined?
Should an analyst take action?
```

## Diagnostic Chain

```text
The local generator publishes current-summary.json plus manifest.json as a Shadow Performance artifact set
-> the generated-summary runtime override mounts the current summary
-> the artifact-backed provider validates the manifest and reads the current summary
-> the authorized read API exposes the current summary
-> the Analyst Console displays the current summary
-> the local generator produces the Promotion Review Readiness Report
```

Promotion Review Readiness consumes existing bounded artifacts. Promotion Review Readiness does not recompute metrics from raw data. The v1 local generator
consumes the generated Shadow Performance artifact set:
`deployment/local-generated/shadow-performance/current-summary.json` plus
`deployment/local-generated/shadow-performance/manifest.json`.

## V1 Limitation

Promotion Review Readiness v1 primarily consumes the generated Shadow Performance Summary artifact set.

Platform Recommendation Evaluation Card checks validate that the consumed Shadow Performance Summary V2 was derived
from the current Platform Recommendation Evaluation Card contract.

## Output

The local/offline generator writes:

```text
deployment/local-generated/promotion-readiness/promotion-review-readiness-report.json
deployment/local-generated/promotion-readiness/manifest.json
```

The report and sibling manifest form the Promotion Review Readiness artifact set. The manifest uses
`PROMOTION_REVIEW_READINESS_ARTIFACT_SET_V1`, `promotion-review-readiness-artifact-set-v1`, the exact report
`generatedAt`, and one canonical `promotion-review-readiness-report.json` file entry with lowercase SHA-256 and
`sizeBytes`. The filename includes `review` so the artifact cannot be confused with promotion approval.

## Status Semantics

Allowed `readinessStatus` values:

```text
INSUFFICIENT_DATA
INCONCLUSIVE
NOT_REVIEWABLE
REVIEWABLE
```

`REVIEWABLE` means human review may begin. `REVIEWABLE` does not mean model promotion approval.
`INCONCLUSIVE` means at least one required diagnostic metric is unavailable and must not be treated as zero, pass, or fail.

`DIAGNOSTIC_ONLY` is governanceStatus, not readinessStatus.

## Minimum Evidence

The report may use `minimumDiagnosticEvidenceRecords` as a local review sufficiency check.

Minimum diagnostic evidence is a review sufficiency check, not a model threshold and not a promotion threshold.

## Diagnostic Checks

`EVALUATION_CARD_VERSION_SUPPORTED` is the current evaluation-card contract diagnostic check.

This check validates that the consumed summary was derived from the current Platform Recommendation Evaluation Card contract.

The report also includes explicit non-decisioning flags, including `notAnalystRecommendation`.

The report carries stored `checkInputs`, including the source Shadow Performance manifest SHA-256. Validators derive
checks, reason codes, and readiness status from those inputs and reject reports where stored checks have been edited.
The SHA-256 values are local integrity and lineage fingerprints over exact local bytes. They are not signatures, producer
authentication, independent attestation, legal proof, or protection against a privileged deployment writer replacing both
artifact and manifest inside the filesystem trust boundary.

Publication is manifest-last and fail-closed: the generator serializes and validates the report, writes temporary report
and manifest files, validates the temporary artifact set, removes the old final manifest, replaces the report, then
replaces the fresh manifest last. Symlink checks and `O_NOFOLLOW` are misuse hardening; local TOCTOU is not advertised as
fully eliminated on every platform. Atomic replacement alone is not power-loss durability unless files and directories are
explicitly synchronized, which is outside this local generator scope.

Published report and manifest timestamps must be RFC3339 UTC `Z` strings with optional 1-9 digit fractional seconds.
The manifest `generatedAt` must exactly equal the report `generatedAt`; equivalent offset encodings such as `+00:00`
are rejected rather than normalized while reading a published artifact set.

## Non-Decisioning Boundary

Promotion Review Readiness does not approve promotion.
Promotion Review Readiness does not recommend threshold changes.
Promotion Review Readiness does not change scoring.
Promotion Review Readiness does not authorize payments.
Promotion Review Readiness does not recommend analyst action.
Promotion Review Readiness does not add API, OpenAPI, UI, workflow, scheduler, or Kafka triggers.
Promotion Review Readiness does not mutate model registry or model artifacts.
Promotion Review Readiness does not read raw dataset JSONL, raw offline evaluation reports, raw Platform Recommendation Evaluation Card displays, raw transaction records,
MongoDB, Kafka, payment data, alert database, fraud case database, model registry, or raw model outputs.

## Local Command

```bash
make promotion-readiness-report
```

This target runs only the local Python generator. It does not start Docker Compose, call API/UI surfaces, or mutate
registry, scoring, payment, alerts, or fraud cases.
