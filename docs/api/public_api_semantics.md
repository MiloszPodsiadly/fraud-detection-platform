# Public API Semantics

Status: current regulated mutation API reference.

## Scope

This document defines the public regulated mutation response fields and conservative status semantics.

`SubmitAnalystDecisionResponse` exposes `alertId`, `decision`, `resultingStatus`, `decisionEventId`, `decidedAt`, and
the mandatory `operation_status`. `UpdateFraudCaseResponse` exposes `operation_status`, `command_id`,
`idempotency_key_hash`, `case_id`, `current_case_snapshot`, `updated_case`, and `recovery_required_reason`. Clients must
interpret projected business fields through `operation_status`; HTTP success alone is not proof of completed mutation
or external finality.

The canonical status set is:

- processing: `IN_PROGRESS`, `EVIDENCE_PREPARING`, `EVIDENCE_PREPARED`, `FINALIZING`
- recovery: `RECOVERY_REQUIRED`, `FINALIZE_RECOVERY_REQUIRED`
- finalized: `FINALIZED_EVIDENCE_PENDING_EXTERNAL`, `FINALIZED_EVIDENCE_CONFIRMED`
- rejected or failed: `REJECTED_EVIDENCE_UNAVAILABLE`, `FAILED_BUSINESS_VALIDATION`

Recovery, preparation, and finalizing responses must not expose the requested resource state as completed. Replay is safe only
for the same idempotency key and canonical intent; a different payload or backend-resolved actor is a conflict.

Local evidence confirmation is not external finality. Local evidence is not external finality.
Pending external evidence is not confirmed. Recovery required is not success, and every recovery status is not success.

Checkpoint renewal preserves lease ownership only; it is not proof of business progress.
Lease renewal preserves the current worker's ownership window only. It is not proof of business progress.
Idempotent processing is not distributed exactly-once delivery.
Signed release or provenance artifacts are release controls, not proof of business correctness.

Every executable command has model version `EVIDENCE_GATED_FINALIZE_V1`. Missing, null, retired, and unknown persisted
versions are rejected; they are not interpreted as the current model. The operator preflight is read-only and does not
rewrite or purge records.

Public errors use the shared platform envelope and must not expose stack traces, raw hashes, lease owners, tokens,
payloads, or internal paths.
