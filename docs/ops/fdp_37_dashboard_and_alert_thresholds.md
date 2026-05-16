# FDP-37 Observability Thresholds

Status: branch evidence and release-review support.

## Purpose

This document defines the dashboard and alert threshold expectations for FDP-37 production-image chaos proof review.
It is operational evidence for release review. It is not production enablement, not production environment
configuration certification, and not bank certification.

FDP-37 is a proof, operations, and release-gate branch. The proof uses a production-like `alert-service`
Docker image/container and a shared Testcontainers network. It does not add production chaos hooks.

## Label Policy

Dashboard labels must stay low-cardinality. Allowed labels are:

- `model_version`
- `state`
- `outcome`
- `reason`
- `checkpoint`
- `threshold`

Do not use command id, alert id, actor id, idempotency key, lease owner, raw exception, request path, or token labels.

## Runtime Thresholds

| Signal | Metric | Threshold | Severity | Owner | Runbook | Safe action | Forbidden action |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Stale owner spike | `regulated_mutation_stale_write_rejected_total` | `increase(...[5m]) >= 5` | warning | fraud-platform-oncall | [Regulated mutation recovery](../runbooks/regulated_mutation_recovery.md) | Inspect recovery queue and lease budget. | Manually edit lease owner. |
| Expired lease spike | `regulated_mutation_stale_write_rejected_total{reason="EXPIRED_LEASE"}` | `increase(...[5m]) >= 3` | warning | fraud-platform-oncall | [Regulated mutation recovery](../runbooks/regulated_mutation_recovery.md) | Run the bounded recovery endpoint. | Replay with a new idempotency key. |
| Recovery conflict | `regulated_mutation_recovery_write_conflict_total` | `increase(...[5m]) > 0` | critical | fraud-platform-oncall | [Regulated mutation recovery](../runbooks/regulated_mutation_recovery.md) | Inspect command state and preserve fencing. | Force command completion. |
| Long-running processing age | `regulated_mutation_recovery_processing_oldest_age_seconds` | `> 120s for 5m` | warning | fraud-platform-oncall | [Regulated mutation recovery](../runbooks/regulated_mutation_recovery.md) | Inspect in-progress commands. | Mark completed manually. |
| Checkpoint renewal blocked | `regulated_mutation_checkpoint_renewal_blocked_total` | `increase(...[10m]) >= 3` | warning | fraud-platform-oncall | [Regulated mutation recovery](../runbooks/regulated_mutation_recovery.md) | Review checkpoint budget and recovery mode. | Disable fencing. |
| Low lease budget | `regulated_mutation_lease_budget_warning_total` | `increase(...[5m]) >= 3` | warning | fraud-platform-oncall | [Regulated mutation recovery](../runbooks/regulated_mutation_recovery.md) | Tune lease budget through release/config PR. | Change runtime state by hand. |

## Release-Gate Thresholds

| Signal | Source | Threshold | Severity | Owner | Safe action | Forbidden action |
| --- | --- | --- | --- | --- | --- | --- |
| Production image chaos job failed | GitHub Actions `fdp37-production-image-chaos` | Any failed required run. | critical | release-owner | Block merge and review artifact output. | Merge with missing proof summary. |
| Required FDP-37 test skipped | Surefire XML | Any required skipped test. | critical | release-owner | Block merge. | Count a skipped test as proof. |
| Missing image provenance | Proof summary | Missing SHA, tag, digest/image id, Dockerfile path, or killed container id. | critical | release-owner | Rebuild the proof image from the current commit. | Use stale local image as merge evidence. |
| Duplicate outbox invariant failed | FDP-37 proof artifact invariant | Any duplicate outbox row. | critical | release-owner | Stop enablement review and investigate. | Delete duplicate rows manually. |
| Duplicate `SUCCESS` audit invariant failed | FDP-37 proof artifact invariant | Any duplicate `SUCCESS` audit row. | critical | release-owner | Stop enablement review and investigate. | Edit the audit chain. |
| Rollback validation missing | `target/fdp37-chaos/fdp37-rollback-validation.md` | Missing artifact or `final_result: FAIL`. | critical | release-owner | Block enablement review. | Treat the template as approval. |

## Required Panels

Dashboard review should include:

- stale-write rejections by reason;
- lease takeover and recovery-required count;
- oldest processing command age;
- checkpoint renewal outcome and blocked renewal count;
- transition latency and recovery conflict count;
- FDP-37 CI proof status;
- image provenance status;
- rollback validation status.

## Review Interpretation

`READY_FOR_ENABLEMENT_REVIEW` is not production enablement. A skipped live in-flight test is not counted as proof.
Rollback validation is release evidence, not production rollback approval.

FDP-37 uses a shared Testcontainers network with stable dependency aliases. This dashboard/runbook does not certify
production Docker Compose networking or production networking.
