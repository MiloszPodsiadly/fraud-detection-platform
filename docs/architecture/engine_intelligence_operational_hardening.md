# Engine Intelligence Operational Hardening

## Purpose

FDP-100 adds operational metrics, retention policy documentation, and index strategy documentation for the existing
engine-intelligence projection, feedback submit, and feedback read surfaces.

## Scope

This is operational hardening only. FDP-100 does not add public API endpoints, UI features, dashboards, global
feedback search, case aggregation, feedback export, model retraining, rule updates, alert severity changes,
fraud-case status changes, approve/decline/block workflows, or payment-authorization behavior.

## Operational Metrics

Metrics are emitted through the existing `AlertServiceMetrics` Micrometer integration. Label values are bounded
enum or constant values only.

| Surface | Metrics | Labels |
| --- | --- | --- |
| Internal projection | `engine_intelligence_projection_attempt_total`, `engine_intelligence_projection_success_total`, `engine_intelligence_projection_omitted_total`, `engine_intelligence_projection_failure_total`, `engine_intelligence_projection_latency_seconds` | `reason` only on omitted/failure |
| Feedback submit | `engine_intelligence_feedback_submit_attempt_total`, `engine_intelligence_feedback_submit_success_total`, `engine_intelligence_feedback_submit_validation_failure_total`, `engine_intelligence_feedback_submit_idempotency_replay_total`, `engine_intelligence_feedback_submit_idempotency_conflict_total`, `engine_intelligence_feedback_submit_audit_failure_total`, `engine_intelligence_feedback_submit_unavailable_total`, `engine_intelligence_feedback_submit_latency_seconds` | `reason` only on unavailable |
| Feedback read | `engine_intelligence_feedback_read_attempt_total`, `engine_intelligence_feedback_read_success_total`, `engine_intelligence_feedback_read_empty_total`, `engine_intelligence_feedback_read_unavailable_total`, `engine_intelligence_feedback_read_validation_failure_total`, `engine_intelligence_feedback_read_audit_failure_total`, `engine_intelligence_feedback_read_latency_seconds` | `reason` only on unavailable |

Metric labels must not include transaction IDs, feedback IDs, submittedBy, customer/account/card/merchant IDs,
correlation IDs, idempotency keys or hashes, request payload hashes, raw endpoint paths, raw query strings, raw
exception messages, tokens, secrets, or stack traces.

## Bounded Reasons

Projection reasons are bounded to `ENGINE_INTELLIGENCE_ABSENT`, `INVALID_PROJECTION_SHAPE`, `STORE_UNAVAILABLE`,
`VALIDATION_FAILED`, and `UNKNOWN_FAILURE`.

Feedback submit reasons are bounded to `VALIDATION_FAILED`, `IDEMPOTENCY_REPLAY`, `IDEMPOTENCY_CONFLICT`,
`AUDIT_FAILURE`, `STORE_UNAVAILABLE`, and `UNKNOWN_FAILURE`.

Feedback read reasons are bounded to `EMPTY_RESULT`, `VALIDATION_FAILED`, `CORRUPTED_STORED_FEEDBACK`,
`AUDIT_FAILURE`, `STORE_UNAVAILABLE`, and `UNKNOWN_FAILURE`.

Unknown or null operational reasons fall back to `UNKNOWN_FAILURE`. Raw exception messages and raw validation details
must never become metric label values.

## Retention Policy

Engine-intelligence projections follow scored transaction retention unless a shorter operational TTL is explicitly
approved. Projection retention is owned by the alert-service storage owner for scored transaction projections.

Feedback records follow analyst feedback governance retention and must not be deleted before audit and governance
requirements are satisfied. Feedback retention is owned by the alert-service governance/audit owner.

Audit records may outlive projection and feedback records according to regulated audit policy.
Audit retention is separate from projection and feedback retention.
Audit evidence must not be deleted as part of projection or feedback rollback.

FDP-100 documents retention policy and does not add automatic feedback deletion, projection deletion, cleanup
schedulers, or TTL indexes. Automatic deletion requires explicit governance approval, retention acceptance, and a
separate implementation review.

If a projection expires in a future governed retention policy, scored transaction reads must continue to work and
engine-intelligence read behavior must remain unavailable/not-projected rather than inventing data.
If feedback exists after projection expiry, the feedback remains governance review evidence and must not be treated
as training labels, ground truth, or executable decisions.
Feedback must not be treated as training labels.
If audit outlives projection or feedback, audit remains the retained record of access and mutation history.

## Index Strategy

The index strategy table columns are collection, access pattern, index, introduced in, risk if missing, and owner.

| Collection | Access pattern | Index | Introduced in | Risk if missing | Owner |
| --- | --- | --- | --- | --- | --- |
| `engine_intelligence_projections` | Lookup/upsert by transactionId | transactionId as document id / Mongo `_id` | FDP-95 | Projection lookup or read degradation | Alert-service projection owner |
| `engine_intelligence_feedback` | Idempotent feedback submit | `submittedBy + transactionId + idempotencyKeyHash` unique, `engine_intelligence_feedback_idempotency_idx` | FDP-98 | Duplicate feedback or idempotency break | Feedback submit owner |
| `engine_intelligence_feedback` | Latest feedback read for one transaction | `transactionId + submittedAt DESC + feedbackId ASC`, `engine_intelligence_feedback_transaction_submitted_feedback_idx` | FDP-99 | Expensive transaction-scoped sort | Feedback read owner |
| `engine_intelligence_feedback` | Operational historical inspection by transaction | `transactionId + createdAt DESC`, `engine_intelligence_feedback_transaction_created_idx` | FDP-98/FDP-99 | Operational historical inspection degradation | Feedback storage owner |

FDP-100 does not add new indexes, remove existing indexes, or introduce a global feedback search or analytics index.

## Audit Relationship

Feedback submit success depends on write audit success. Feedback read success depends on sensitive read audit success
in fail-closed modes. Audit failure is operationally visible through bounded metrics and must not be bypassed to make
reads or writes succeed.

## Operational Boundaries

FDP-100 does not call ML, rules, scoring, payment authorization, fraud-case mutation, alert severity mutation,
outbox publication, model training, training export, or dashboard code. Feedback remains analyst perception and
governance review input only.
