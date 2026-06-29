# Fraud Feedback Loop

Status: FDP-122 backend/API write-action audit outbox migration with FDP-121 Analyst Console capture v1.

## Scope

FDP-121 records one bounded analyst review outcome for an existing scored transaction:

`POST /api/v1/transactions/scored/{transactionId}/feedback`

The active record can be read with:

`GET /api/v1/transactions/scored/{transactionId}/feedback`

The source of truth is the alert-service `fraud_feedback_records` repository. Write-action audit intent is stored in
the alert-service `write_action_audit_outbox` repository. Recommendation generation remains owned by
fraud-scoring-service. FDP-121 stores passive snapshots from the scored transaction projection, Engine Intelligence
projection, and already-projected Analyst Recommendation when safely available.

## Contract

Allowed feedback labels:

- `CONFIRMED_FRAUD`
- `CONFIRMED_LEGITIMATE`
- `INCONCLUSIVE`
- `NEEDS_MORE_INFO`

Allowed analyst decisions:

- `MARKED_FRAUD`
- `MARKED_LEGITIMATE`
- `MARKED_INCONCLUSIVE`
- `REQUESTED_MORE_INFO`

The only label source in v1 is `ANALYST_REVIEW`; the only status is `RECORDED`.

The decision and label pair must match:

- `MARKED_FRAUD` -> `CONFIRMED_FRAUD`
- `MARKED_LEGITIMATE` -> `CONFIRMED_LEGITIMATE`
- `MARKED_INCONCLUSIVE` -> `INCONCLUSIVE`
- `REQUESTED_MORE_INFO` -> `NEEDS_MORE_INFO`

Feedback reason codes are controlled by the `FraudFeedbackReasonCode` allowlist. Unknown uppercase strings are rejected
instead of accepted by format alone. Reason codes must also match the selected feedback label:

- `CONFIRMED_FRAUD`: `CUSTOMER_CONFIRMED_FRAUD`, `DOCUMENTATION_CONFIRMED_FRAUD`, `CHARGEBACK_SIGNAL`,
  `ACCOUNT_TAKEOVER_INDICATOR`, `ANALYST_CONFIRMED_FRAUD`
- `CONFIRMED_LEGITIMATE`: `CUSTOMER_CONFIRMED_LEGITIMATE`, `DOCUMENTATION_CONFIRMED_LEGITIMATE`,
  `MERCHANT_CONFIRMED`, `FALSE_POSITIVE_PATTERN`, `ANALYST_CONFIRMED_LEGITIMATE`
- `INCONCLUSIVE`: `INSUFFICIENT_EVIDENCE`, `ANALYST_INCONCLUSIVE`
- `NEEDS_MORE_INFO`: `NEEDS_CUSTOMER_CONTACT`, `INSUFFICIENT_EVIDENCE`, `ANALYST_NEEDS_MORE_INFO`

Every feedback record requires at least one bounded, label-compatible reason code. Feedback without reason codes is
rejected.

One active feedback record is allowed per transaction. A second POST returns `409 CONFLICT` and does not overwrite the
existing record. Existing transactions without feedback return `404 NOT_FOUND` on the feedback read endpoint.

## Boundaries

Analyst feedback labels are review outcomes and future evaluation signals, not certified legal ground truth.

FDP-121 does not:

- authorize payment
- approve, decline, or block transactions
- create fraud cases
- trigger workflow
- change scoring
- update Analyst Recommendation
- recompute Engine Intelligence
- train or retrain ML models
- export datasets
- promote models
- recommend or change thresholds
- publish Kafka feedback events

Notes are optional, bounded to 500 characters, stored internally for analyst context only, and not used for ML training
or dataset export in FDP-121. Validation rejects unsafe raw/internal terms. Notes are not audited and are not returned
raw in feedback responses; the API exposes only `notesPresent`.

## Authorization And Audit

Feedback write requires `fraud-feedback:write` and an authenticated analyst actor. Missing actor context fails closed
before persistence or audit.

Feedback read requires `fraud-feedback:read`.

`transaction-monitor:read` is not sufficient to create or read feedback.

Every successful feedback write persists a platform audit intent with the actor, transaction id, transaction
correlation, feedback id, feedback label, created timestamp, and recorded status. Audit metadata does not include raw
notes, raw customer payloads, raw ML payloads, raw feature vectors, or raw evidence.

FDP-122 requires durable feedback persistence and durable write-action audit outbox persistence before returning `201`.
The response does not mean `AuditService` has already emitted the final audit event. `FraudFeedbackService` no longer
calls `AuditService` directly on the normal success path; `WriteActionAuditOutboxPublisher` is responsible for later
publication.

Feedback write uses the existing `RegulatedMutationTransactionRunner`. In transaction mode `REQUIRED`, feedback and
outbox writes participate in the configured local Mongo transaction. In default local mode `OFF`, outbox persistence
failure returns `FRAUD_FEEDBACK_AUDIT_OUTBOX_UNAVAILABLE` and the service performs bounded local cleanup of the saved
feedback record. That fallback is not claimed as full atomicity.

`FRAUD_FEEDBACK_AUDIT_OUTBOX_UNAVAILABLE` is exposed as `503` because the service cannot safely report success without
the durable audit intent.

The write-action audit outbox is published by an internal scheduled trigger. The scheduler is enabled by default with
`app.audit.outbox.publisher.enabled=true`, uses `app.audit.outbox.publisher.fixed-delay-ms=30000`, and stores a
five-minute default claim lease with `app.audit.outbox.publisher.claim-lease-ms=300000`. The publisher atomically claims
records with `PUBLISHING` before calling `AuditService`, so concurrent scheduler runs skip records they cannot claim.
`PUBLISHING` is a leased claim state, not a terminal state: the record stores `claimExpiresAt`, fresh claims remain owned
by their current publisher, and stale PUBLISHING records are recoverable by a later scheduler run after
`claimExpiresAt <= now`. There is no public recovery endpoint, admin UI, public outbox API, public dataset API, Kafka
feedback publishing, or manual recovery API in FDP-122. Audit publication is at-least-once. A crash after
`AuditService.audit` but before marking the outbox record published may cause re-publication after lease expiry, so
exact-once audit effects require downstream `AuditService` idempotency or consumer-side deduplication.

## Dataset Governance Rules

`CONFIRMED_FRAUD` and `CONFIRMED_LEGITIMATE` are evaluation candidates only. They are not certified legal ground truth
and are not training labels in FDP-122.

`INCONCLUSIVE` and `NEEDS_MORE_INFO` must not be used as positive or negative labels. They must be excluded from binary
evaluation or handled as a separate unresolved bucket.

Notes are analyst-readable context only; notes are not training input in FDP-122 and must not be exported to datasets
in FDP-122. Any future notes export requires a separate governance and sanitization scope.

Feedback reason codes are bounded signals, not raw evidence. Feedback does not trigger training, retraining, model
promotion, threshold change, or dataset export.

FDP-122 includes a conservative internal dataset-field allowlist for governance checks. FDP-123 adds an internal bounded
fraud feedback dataset builder for `fraud_feedback_records` only. The builder is separate from Engine Intelligence
Feedback Dataset Export, does not read `engine_intelligence_feedback`, does not use Python `feedback_dataset.py` as
source of truth, and does not expose a public dataset API, UI, scheduler, CLI, Kafka publication, or automatic runtime
file export. Its JSONL uses pseudonymous references that are not anonymization and not a privacy boundary. The dataset is
for future evaluation only; it is not training data, not certified legal ground truth, and not a final decision.

## Analyst Console Capture

The Transaction Risk Intelligence panel includes an `Analyst Feedback` section for the expanded scored transaction.
It loads existing feedback, renders a no-feedback state, allows explicit submit only when no active feedback exists,
and displays duplicate, validation, permission, not-found, unavailable, and network error states safely.

The UI validates matching labels/decisions, reason-code allowlist values, label-compatible reason codes,
reason-code bounds, note length, and unsafe terms before submit. It does not submit automatically, does not apply
Analyst Recommendation, and does not call scoring, recommendation generation, workflow, case, payment, model,
threshold, bulk, or export endpoints.
