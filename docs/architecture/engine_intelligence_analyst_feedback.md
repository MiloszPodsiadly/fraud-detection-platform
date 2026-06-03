# Engine Intelligence Analyst Feedback

## Purpose

FDP-98 captures structured analyst feedback about engine intelligence usefulness and perceived accuracy. Feedback is captured and audited, not executed.

## Scope

The feedback surface is transaction-scoped and uses `POST /api/v1/transactions/scored/{transactionId}/engine-intelligence/feedback`. It records bounded analyst feedback for the existing engine intelligence projection shown in the analyst console.

## Non-goals

FDP-98 does not automatically change scoring, alert severity, fraud case status, approval, decline, blocking, payment authorization, rules, or model training. Feedback is not a training label, ground truth, or model correction.

## Endpoint

The endpoint accepts only a scored `transactionId` matching `^[A-Za-z0-9._:-]{1,128}$`. Missing scored transactions return the same controlled not-found behavior as the FDP-96 read endpoint.

## Authorization

Feedback requires write/review authority, not read-only permission. `TRANSACTION_MONITOR_READ` can read engine intelligence, but cannot submit feedback.

## Feedback Model

The feedback model stores `feedbackType`, `usefulness`, `accuracyAssessment`, `engineIntelligenceAvailable`, optional bounded `selectedReasonCodes`, and optional bounded `fraudCaseId`. Server-generated fields are `feedbackId`, `transactionId`, `submittedBy`, `submittedAt`, `correlationId`, and `createdAt`.

## Structured-Only V1

FDP-98 v1 has no free-text feedback. The client and server use bounded enums and bounded reason codes only.

## Append-Only Persistence

Feedback is stored append-only.
Feedback is stored append-only in `engine_intelligence_feedback`. Submitting feedback does not overwrite previous feedback records.

## Idempotency

Idempotency prevents duplicate submissions. The same actor, transaction, and idempotency key returns the existing feedback result instead of appending another record.

## Audit Trail

Successful feedback creates an audit entry. The audit entry includes bounded feedback metadata and must not include raw request bodies, raw payloads, tokens, secrets, endpoint strings, stacktraces, internal projection objects, or raw engine intelligence responses.

## UI Behavior

The analyst console renders a small structured feedback panel below `EngineIntelligencePanel` for the same transaction. The UI sends only selected bounded values and shows safe success or error states.

## No Decisioning

Feedback is captured and audited, not executed. FDP-98 does not automatically change scoring, alert severity, fraud case status, approval, decline, blocking, payment authorization, rules, or model training.

## No Retraining Or Rule Updates

Feedback is not a training label, ground truth, or model correction. Future model evaluation/export requires separate governance review.

## Raw/Internal Leakage Prevention

The feedback request rejects raw/internal terms in accepted strings and does not store raw request bodies, raw engine intelligence responses, tokens, secrets, stacktraces, feature snapshots, raw payloads, or internal aggregation objects.

## Future Scopes

Case-level aggregation, model evaluation exports, retraining workflows, rule update workflows, and any operational outcome changes are separate future scopes that require explicit governance review.
