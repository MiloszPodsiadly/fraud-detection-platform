# Fraud Feedback Loop

Status: FDP-121 backend/API and Analyst Console capture v1.

## Scope

FDP-121 records one bounded analyst review outcome for an existing scored transaction:

`POST /api/v1/transactions/scored/{transactionId}/feedback`

The active record can be read with:

`GET /api/v1/transactions/scored/{transactionId}/feedback`

The source of truth is the alert-service `fraud_feedback_records` repository. Recommendation generation remains owned
by fraud-scoring-service. FDP-121 stores passive snapshots from the scored transaction projection, Engine Intelligence
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

Every successful feedback write emits a platform audit event with the actor, transaction id, transaction correlation,
feedback id, feedback label, created timestamp, and recorded status. Audit metadata does not include raw notes, raw
customer payloads, raw ML payloads, raw feature vectors, or raw evidence.

FDP-121 emits audit after successful persistence. Transactional or outbox-style audit hardening is future scope for
shared write-action infrastructure and is not implemented ad hoc in this feedback slice.

## Analyst Console Capture

The Transaction Risk Intelligence panel includes an `Analyst Feedback` section for the expanded scored transaction.
It loads existing feedback, renders a no-feedback state, allows explicit submit only when no active feedback exists,
and displays duplicate, validation, permission, not-found, unavailable, and network error states safely.

The UI validates matching labels/decisions, reason-code allowlist values, label-compatible reason codes,
reason-code bounds, note length, and unsafe terms before submit. It does not submit automatically, does not apply
Analyst Recommendation, and does not call scoring, recommendation generation, workflow, case, payment, model,
threshold, bulk, or export endpoints.
