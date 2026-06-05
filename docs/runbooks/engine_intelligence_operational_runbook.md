# Engine Intelligence Operational Runbook

## Overview

This runbook covers operational triage for engine-intelligence projection, transaction-scoped read display, feedback
submit, feedback read, and their audit dependencies. FDP-100 adds observability and documentation only; it does not
enable rollout, add dashboards, expose global search, export feedback, or change decisioning.

Initial alert thresholds below are conservative placeholders and must be tuned after rollout observation. They are
not final production SLOs.

## Surfaces

| Surface | Operational note |
| --- | --- |
| Producer emission | Controlled by the existing producer flag from earlier FDP scope; confirm state before rollout. |
| Internal projection | Writes bounded `engine_intelligence_projections`; failure must not break scored transaction projection. |
| Engine intelligence read API | Existing transaction-scoped read API only; no global search or case aggregation. |
| UI display | Existing transaction detail display only; unavailable/not-projected state must remain safe. |
| Feedback submit | Existing transaction-scoped append-only feedback submit with write audit. |
| Feedback read | Existing transaction-scoped bounded first page with sensitive read audit. |
| Sensitive read audit | Fail-closed modes must return bounded 503 instead of bypassing audit. |
| Feedback write audit | Feedback submit must not report created unless feedback and audit complete. |

## Feature Flags

| Rollout stage | Entry criteria | Exit criteria | Rollback trigger | Owner | Required metrics | Required audit health | Storage monitoring | Feature flag state |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Stage 0: default disabled / no user impact | Current baseline deployed | Metrics visible in non-user path | Any unexpected API/UI change | Platform owner | Projection, submit, read attempts absent or baseline | Audit healthy | Baseline collection size known | Producer disabled unless separately approved |
| Stage 1: internal sandbox | Test data only | No raw label values, no audit bypass | Projection failure spike | Platform owner | Projection attempt/success/omitted/failure/latency | Write/read audit healthy | Projection growth reviewed | Sandbox-only |
| Stage 2: single environment / test users | Store and audit health green | Feedback submit/read stable for test users | Submit/read unavailable spike | Alert-service owner | Submit/read attempts, success, empty, unavailable, latency | Feedback write and read audit healthy | Feedback growth reviewed | Limited environment |
| Stage 3: limited analyst cohort | Prior stage exit accepted | No p95/p99 latency regression and no audit failures | Audit failure greater than zero in regulated/fail-closed modes | Operations owner | All FDP-100 metrics visible | Sensitive read audit and feedback write audit healthy | Growth within baseline | Limited cohort |
| Stage 4: broader read-only rollout | Limited cohort stable | Missing projection tolerated by consumers | Feedback read unavailable spike | Product operations owner | Read success/empty/unavailable, projection omitted | Read audit healthy | Projection and feedback storage watched | Broader read-only |
| Stage 5: governance review surfaces | Governance owner accepts retention | Review-only feedback access stable | Idempotency conflict or corrupted-storage spike | Governance owner | Submit conflict/replay, read corrupted-storage | Audit trail healthy | Retention policy accepted | Governance review only |

Rollout checklist:

- Producer flag state confirmed.
- Projection store healthy.
- Feedback submit endpoint healthy.
- Feedback read endpoint healthy.
- Sensitive read audit healthy.
- Feedback write audit healthy.
- Metrics visible.
- Storage growth monitored.
- Retention policy accepted.
- Existing indexes deployed.
- Rollback owner assigned.
- UI unavailable/not-projected behavior verified.
- Consumers tolerate missing `engineIntelligence`.

## Metrics

- `engine_intelligence_projection_attempt_total`
- `engine_intelligence_projection_success_total`
- `engine_intelligence_projection_omitted_total{reason}`
- `engine_intelligence_projection_failure_total{reason}`
- `engine_intelligence_projection_latency_seconds`
- `engine_intelligence_feedback_submit_attempt_total`
- `engine_intelligence_feedback_submit_success_total`
- `engine_intelligence_feedback_submit_validation_failure_total`
- `engine_intelligence_feedback_submit_idempotency_replay_total`
- `engine_intelligence_feedback_submit_idempotency_conflict_total`
- `engine_intelligence_feedback_submit_audit_failure_total`
- `engine_intelligence_feedback_submit_unavailable_total{reason}`
- `engine_intelligence_feedback_submit_latency_seconds`
- `engine_intelligence_feedback_read_attempt_total`
- `engine_intelligence_feedback_read_success_total`
- `engine_intelligence_feedback_read_empty_total`
- `engine_intelligence_feedback_read_unavailable_total{reason}`
- `engine_intelligence_feedback_read_validation_failure_total`
- `engine_intelligence_feedback_read_audit_failure_total`
- `engine_intelligence_feedback_read_latency_seconds`

Metric labels must not include raw IDs, submittedBy, raw endpoint paths, raw query strings, tokens, secrets, stack
traces, or exception messages.

## Alerts

Initial thresholds are conservative placeholders and must be tuned after rollout observation.

| Signal | Placeholder condition | Action |
| --- | --- | --- |
| Projection failure rate | `projection_failure_rate > threshold for duration` | Check Mongo health and recent deployments. |
| Projection omitted spike | `projection_omitted_rate spike` | Check producer event compatibility and projection validation. |
| Feedback submit validation spike | validation failures spike | Check client payload compatibility; do not loosen validation. |
| Feedback submit audit failure | `feedback_submit_audit_failure > 0` in regulated mode | Treat as audit dependency incident. |
| Feedback read audit failure | `feedback_read_audit_failure > 0` in fail-closed mode | Keep endpoint fail-closed and restore audit. |
| Feedback read unavailable | unavailable spike | Check feedback store, read policy, and audit health. |
| Corrupted stored feedback | `corrupted_stored_feedback_detected > 0` | Preserve records and escalate storage/audit owner. |
| Idempotency conflict | conflict spike | Check client key reuse behavior. |
| Storage growth | growth above baseline | Review retention and projection/feedback volume. |
| Latency | p95/p99 above threshold | Check Mongo, audit latency, and recent deployments. |

Do not create alerts keyed by transactionId, feedbackId, submittedBy, customer/account/card/merchant ID, endpoint
path, or raw exception message.

## Known Failure Modes

- Projection store unavailable.
- Projection validation spike.
- Feedback submit validation spike.
- Feedback submit audit failure.
- Feedback read audit failure.
- Feedback read corrupted-storage failure.
- Idempotency conflict spike.
- Sensitive read audit unavailable.
- Storage growth spike.

## Triage Steps

Symptom: feedback read returns 503.

Check:

- `engine_intelligence_feedback_read_unavailable_total`.
- `engine_intelligence_feedback_read_audit_failure_total`.
- Mongo health.
- `CORRUPTED_STORED_FEEDBACK` reason count.
- Recent deployments.

Action:

- Keep endpoint fail-closed.
- Do not bypass read policy.
- Do not expose raw Mongo documents.
- Escalate storage or audit owner.

Symptom: feedback submit fails after persistence attempt.

Check:

- `engine_intelligence_feedback_submit_audit_failure_total`.
- `engine_intelligence_feedback_submit_unavailable_total`.
- Feedback write audit health.
- Transaction mode configuration.

Action:

- Preserve feedback and audit evidence.
- Do not report created feedback without audit success.
- Restore audit/store dependency before expanding rollout.

Symptom: projection failures spike.

Check:

- Projection failure and omitted reason distribution.
- Mongo health.
- Producer flag state and recent event-contract rollout.

Action:

- Keep base scored transaction projection isolated.
- Do not use projection failures to alter alert decisions.
- Roll back producer emission if needed.

## Rollback Steps

Rollback disables or degrades surfaces safely. Rollback does not erase evidence.

- Disable producer emission flag if needed.
- Stop or hide feedback submit if audit or storage is degraded.
- Keep read-only surfaces safe and fail-closed where policy requires.
- Preserve audit records.
- Preserve feedback records unless retention policy approves deletion.
- Verify consumers tolerate missing `engineIntelligence`.
- Verify UI handles unavailable/not-projected.
- Verify feedback read fails closed.
- Verify projection cleanup does not break scored transaction reads.

Rollback triggers:

- Projection write failure spike.
- Projection omitted spike.
- Feedback submit failure spike.
- Feedback read unavailable spike.
- Write audit failure spike.
- Read audit failure spike.
- Storage growth anomaly.
- p95/p99 latency regression.
- Corrupted stored feedback validation spike.
- Idempotency conflict spike.

## Retention Policy

Projection retention follows scored transaction retention unless shorter operational TTL is explicitly approved.
Feedback records follow analyst feedback governance retention. Audit may outlive both projection and feedback.
FDP-100 does not add automatic deletion, cleanup jobs, or TTL indexes.

## Index Strategy

Operational index strategy is documented in
[Engine Intelligence Operational Hardening](../architecture/engine_intelligence_operational_hardening.md).

## Audit Expectations

Feedback write audit must succeed before a new feedback response is reported as created. Feedback read audit must
remain enabled; in fail-closed mode audit failure returns 503. Audit records must not be deleted during rollback.

## Do-Not-Do List

- Do not delete audit records.
- Do not delete feedback records without retention approval.
- Do not bypass read policy.
- Do not expose raw Mongo documents.
- Do not disable audit to make reads work.
- Do not treat feedback as ground truth.
- Do not turn feedback into training labels during rollback.
- Do not bypass validation.
- Do not add analytics dashboards, global feedback search, case aggregation, or feedback export.
- Do not add retraining, rule updates, approve/decline/block workflows, payment authorization, alert severity changes, or fraud-case status changes.
