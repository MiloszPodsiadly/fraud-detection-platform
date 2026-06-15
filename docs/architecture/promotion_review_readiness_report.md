# Promotion Review Readiness Report

Status: FDP-111 local/offline diagnostic report artifact.

FDP-111 is a diagnostic report only. It consumes existing bounded artifacts and answers only:

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
FDP-109 generates current-summary.json
-> FDP-110 mounts/wires generated summary into local runtime
-> FDP-108 provider reads current summary
-> FDP-106 API exposes current summary
-> FDP-107 dashboard displays current summary
-> FDP-111 generates Promotion Review Readiness Report
```

FDP-111 consumes existing bounded artifacts. FDP-111 does not recompute metrics from raw data. The v1 local generator
consumes the FDP-109 generated `deployment/local-generated/shadow-performance/current-summary.json` artifact.

## Output

The local/offline generator writes:

```text
deployment/local-generated/promotion-readiness/promotion-review-readiness-report.json
```

The filename includes `review` so the artifact cannot be confused with promotion approval.

## Status Semantics

Allowed `readinessStatus` values:

```text
INSUFFICIENT_DATA
NOT_REVIEWABLE
REVIEWABLE
```

`REVIEWABLE` means human review may begin. `REVIEWABLE` does not mean model promotion approval.

`DIAGNOSTIC_ONLY` is governanceStatus, not readinessStatus.

## Minimum Evidence

The report may use `minimumDiagnosticEvidenceRecords` as a local review sufficiency check.

Minimum diagnostic evidence is a review sufficiency check, not a model threshold and not a promotion threshold.

## Non-Decisioning Boundary

FDP-111 does not approve promotion.
FDP-111 does not recommend threshold changes.
FDP-111 does not change scoring.
FDP-111 does not authorize payments.
FDP-111 does not recommend analyst action.
FDP-111 does not add API, OpenAPI, UI, workflow, scheduler, or Kafka triggers.
FDP-111 does not mutate model registry or model artifacts.
FDP-111 does not read raw FDP-102 JSONL, raw FDP-103 evaluation reports, raw FDP-104 displays, raw transaction records,
MongoDB, Kafka, payment data, alert database, fraud case database, model registry, or raw model outputs.

## Local Command

```bash
make promotion-readiness-report
```

This target runs only the local Python generator. It does not start Docker Compose, call API/UI surfaces, or mutate
registry, scoring, payment, alerts, or fraud cases.

## PR Metadata

Suggested PR title:

```text
FDP-111: Add diagnostic Promotion Review Readiness Report foundation
```

Suggested PR body:

```markdown
## Summary

Adds FDP-111: a local/offline diagnostic Promotion Review Readiness Report foundation.

The report answers only whether there is enough bounded diagnostic evidence for a human promotion review to begin.

It does not approve promotion, recommend thresholds, mutate registries, change scoring, authorize payments, trigger analyst actions, run schedulers, or emit Kafka events.

## Included

- `PromotionReviewReadinessReport v1` schema/contract.
- Local/offline report generator.
- Output artifact:
  `deployment/local-generated/promotion-readiness/promotion-review-readiness-report.json`.
- Makefile target:
  `promotion-readiness-report`.
- Diagnostic checks and reason codes.
- Diagnostic-only governance flags.
- Non-decisioning banner.
- Atomic publish with temp validation and no overwrite on failure.
- Docs for the report boundary.
- Tests and architecture guards for no promotion/threshold/payment/scoring/Kafka/scheduler creep.

## Inputs

FDP-111 consumes bounded diagnostic artifacts only, primarily:

- FDP-109 generated `current-summary.json`;
- existing Model Card / Shadow Performance Summary artifacts where available.

FDP-111 does not read raw transaction records, MongoDB, Kafka, payment data, alert database, fraud case database, or model registry.

## Readiness Semantics

Allowed readiness statuses:

- `INSUFFICIENT_DATA`
- `NOT_REVIEWABLE`
- `REVIEWABLE`

`REVIEWABLE` means only that human review may begin.

It does not mean promote the model, deploy the model, change thresholds, authorize payments, or recommend analyst action.

## Out of Scope

- Promotion approval.
- Promotion workflow.
- Automatic model promotion.
- Threshold recommendation.
- Threshold switching.
- Champion/challenger activation.
- Model registry mutation.
- Model artifact mutation.
- Retraining.
- Online scoring changes.
- Kafka-triggered generation or promotion.
- Production scheduler.
- Cron/background daemon.
- Payment authorization.
- Automatic approve / decline / block.
- Analyst recommendation logic.
- New backend API endpoint.
- OpenAPI expansion.
- Dashboard UI.
- Raw FDP-102/FDP-103/FDP-104 display.
```
