# Engine Intelligence Analyst Feedback

## Purpose

FDP-98 captures structured analyst feedback about engine intelligence usefulness and perceived accuracy. Feedback is captured and audited, not executed.

## Scope

The feedback surface is transaction-scoped and uses `POST /api/v1/transactions/scored/{transactionId}/engine-intelligence/feedback`. It records bounded analyst feedback for the existing engine intelligence projection shown in the analyst console.

## Non-goals

FDP-98 does not automatically change scoring, alert severity, fraud case status, approval, decline, blocking, payment authorization, rules, or model training. Feedback is not a training label, ground truth, model correction, feedback analytics surface, or export workflow.

## Endpoint

The endpoint accepts only a scored `transactionId` matching `^[A-Za-z0-9._:-]{1,128}$`. Missing scored transactions return the same controlled not-found behavior as the FDP-96 read endpoint.

## Authorization

Feedback requires write/review authority, not read-only permission. `TRANSACTION_MONITOR_READ` can read engine intelligence, but cannot submit feedback.

## Feedback Model

The feedback model stores `feedbackType`, `usefulness`, `accuracyAssessment`, `engineIntelligenceAvailable`, optional bounded `selectedReasonCodes`, the canonical request payload hash used for idempotency, and server-owned audit metadata. Public submit responses expose only `feedbackId`, `transactionId`, `engineIntelligenceAvailable`, `feedbackType`, `usefulness`, `accuracyAssessment`, `selectedReasonCodes`, `submittedAt`, and `operationStatus`.

FDP-98 v1 is transaction-scoped. The feedback request does not accept client-supplied `fraudCaseId`, does not persist it, and does not audit it. Future case context requires backend verification of the transaction-to-case relationship before any case-level field can be accepted or derived.

## Structured-Only V1

FDP-98 v1 has no free-text feedback. The client and server use bounded enums and bounded reason codes only. The current UI has no reason-code selector, so it submits `selectedReasonCodes` as an empty list and does not reuse `accuracyAssessment` enum values as reason codes. The backend accepts bounded `selectedReasonCodes` for future compatibility, but future UI support must submit only reason codes selected from bounded engine-intelligence data or a public allowlist.

`NOT_HELPFUL` is usefulness feedback, not automatically a disagreement review. `ENGINE_DISAGREEMENT_REVIEW` requires explicit disagreement context or a future dedicated UI control.

## Append-Only Persistence

Feedback is stored append-only.
Feedback is stored append-only in `engine_intelligence_feedback`. Submitting feedback does not overwrite previous feedback records.

## Idempotency

Idempotency prevents duplicate submissions. The same actor, transaction, idempotency key, and canonical request payload hash returns the existing feedback result instead of appending another record. Reusing the same idempotency key with a different canonical request payload returns conflict.

## Audit Trail

Successful feedback creates an audit entry. The audit entry includes bounded feedback metadata and must not include raw request bodies, raw payloads, tokens, secrets, endpoint strings, stacktraces, internal projection objects, client-supplied case context, or raw engine intelligence responses.

A feedback submission is not reported as `CREATED` unless the feedback record and success audit both complete. In regulated transaction mode, save and audit rely on the regulated mutation transaction boundary. In local transaction mode `OFF`, the service compensates a fresh saved feedback record if audit fails after the save. This `OFF`-mode compensation is a local compatibility fallback, not a replacement for regulated transactional behavior.

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
