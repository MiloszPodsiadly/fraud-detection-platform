# Public API Semantics

Status: current public API semantics reference.

## Scope

This document explains how public API statuses should be read by clients and reviewers. It documents current
semantics only; it does not enable FDP-29 production mode or change API contracts.

## General Rules

- HTTP success does not always mean a business mutation is complete.
- Idempotent replay returns the previously bound result for the same canonical intent.
- A conflicting idempotency key and intent must be treated as a conflict, not a retry.
- Local evidence confirmation is not external finality, legal notarization, or WORM proof.
- Checkpoint renewal preserves lease ownership only; it is not proof of business progress.
- Lease renewal preserves the current worker's ownership window only; it is not a business progress claim.
- Signed release or provenance artifacts are release controls, not proof of business correctness.

## Public Response Fields

`SubmitAnalystDecisionResponse` currently exposes:

| Field | Meaning | Safety note |
| --- | --- | --- |
| `alertId` | Alert identifier for the decision response. | Not a proof artifact. |
| `decision` | Analyst decision value. | Must be interpreted with `operation_status`. |
| `resultingStatus` | Alert status projected by the service. | HTTP 200 alone is not finality. |
| `decisionEventId` | Decision event identifier when available. | Kafka delivery remains at-least-once. |
| `decidedAt` | Service decision timestamp. | Not external timestamp proof. |
| `operation_status` | Regulated mutation public status. | Primary status for mutation interpretation. |

`UpdateFraudCaseResponse` currently exposes:

| Field | Meaning | Safety note |
| --- | --- | --- |
| `operation_status` | Regulated mutation public status. | Recovery statuses are not success. |
| `command_id` | Regulated command identifier. | Operational identifier only. |
| `idempotency_key_hash` | Hash of the idempotency key. | Raw idempotency keys are not exposed. |
| `case_id` | Fraud case identifier. | Not external finality proof. |
| `current_case_snapshot` | Prior case projection when available. | Snapshot is local service data. |
| `updated_case` | Updated case projection when available. | Must be interpreted with `operation_status`. |
| `recovery_required_reason` | Safe recovery reason when recovery is required. | Recovery required is not success. |

## Status Families

| Family | Statuses | Meaning | Non-claim |
| --- | --- | --- | --- |
| Rejected before mutation | `REJECTED_BEFORE_MUTATION`, `REJECTED_EVIDENCE_UNAVAILABLE` | Request did not create a valid business mutation. | Not a committed result. |
| Processing | `IN_PROGRESS`, `EVIDENCE_PREPARING`, `EVIDENCE_PREPARED`, `FINALIZING` | Command is accepted or owned by a worker. Retry with the same idempotency key. | Not progress proof or success. |
| Recovery | `RECOVERY_REQUIRED`, `FINALIZE_RECOVERY_REQUIRED`, `COMMIT_UNKNOWN` | Operator or recovery path must inspect durable evidence. | Not success, rollback, or external finality. |
| Local visible commit | `COMMITTED_EVIDENCE_PENDING`, `FINALIZED_EVIDENCE_PENDING_EXTERNAL` | Local mutation and local evidence are visible or pending confirmation. | Not externally confirmed. |
| Confirmed local evidence | `COMMITTED_EVIDENCE_CONFIRMED`, `FINALIZED_EVIDENCE_CONFIRMED` | Current local evidence policy is satisfied. | Not WORM, legal proof, or distributed ACID. |
| Degraded commit | `COMMITTED_EVIDENCE_INCOMPLETE` | Business state may be committed while evidence completion degraded. | Not complete evidence and not rollback. |
| Business validation failure | `FAILED_BUSINESS_VALIDATION` | Business validation rejected the mutation. | Not a committed requested decision. |

FINALIZED_VISIBLE is a compatibility-visible status for older repair/replay paths, but the current regulated mutation
public-status mapper reports that state as `FINALIZED_EVIDENCE_PENDING_EXTERNAL` rather than a stronger completion
claim. FINALIZED_VISIBLE is not external confirmation.

## Status Truth Table

| Status | Current semantics | Required non-claim |
| --- | --- | --- |
| `REJECTED_BEFORE_MUTATION` | Request was rejected before a valid mutation was committed. | Not a committed decision. |
| `IN_PROGRESS` | Command is accepted or currently owned by a worker. | Not success and not progress proof. |
| `RECOVERY_REQUIRED` | Durable state requires operator or recovery workflow inspection. | Recovery required is not success. |
| `COMMIT_UNKNOWN` | Business commit visibility cannot be concluded from the immediate response. | Not success or rollback. |
| `EVIDENCE_PREPARING` | Evidence-gated path is preparing local evidence. | Not committed business success. |
| `EVIDENCE_PREPARED` | Evidence-gated path prepared local evidence. | Not external finality. |
| `FINALIZING` | Evidence-gated path is finalizing local mutation state. | Not complete or externally confirmed. |
| `FINALIZED_VISIBLE` | Compatibility status for older repair/replay paths. | Current mapper avoids stronger finality. |
| `FINALIZED_EVIDENCE_PENDING_EXTERNAL` | Local finalized state is visible while external evidence remains pending. | Pending external evidence is not confirmed. |
| `FINALIZED_EVIDENCE_CONFIRMED` | Current local evidence policy is satisfied for finalize. | Not WORM, legal proof, or distributed ACID. |
| `REJECTED_EVIDENCE_UNAVAILABLE` | Evidence-gated path rejected because required evidence was unavailable. | Not committed success. |
| `FAILED_BUSINESS_VALIDATION` | Business validation rejected the requested mutation. | Not a committed requested decision. |
| `FINALIZE_RECOVERY_REQUIRED` | Finalize path requires recovery inspection. | Not success or external finality. |
| `COMMITTED_EVIDENCE_PENDING` | Legacy path locally committed business state with evidence still pending. | Pending evidence is not confirmed. |
| `COMMITTED_EVIDENCE_CONFIRMED` | Current local evidence policy is satisfied for commit. | Local evidence is not external finality. |
| `COMMITTED_EVIDENCE_INCOMPLETE` | Business state may be committed while evidence completion degraded. | Not complete evidence and not rollback. |

## Replay Behavior

Replay is safe only with the same idempotency key and the same canonical intent. Replay does not imply
distributed exactly-once processing; downstream consumers still need event-level deduplication.

## Recovery Behavior

Recovery APIs are admin/ops workflows. Recovery visibility is not business approval, external finality, or
production enablement. Sensitive recovery reads must be audited and redacted.

## Error Semantics

Public error responses use the platform error envelope documented in `api_error_contract.md`: `timestamp`, `status`,
`error`, `message`, and `details`.

Endpoint-specific stable reason strings, such as `code:MISSING_IDEMPOTENCY_KEY` or
`reason:FRAUD_CASE_VALIDATION_FAILED`, are safe only when carried as bounded response details or otherwise explicitly
documented for that endpoint. They are not a replacement for the shared envelope.

Public errors must not expose stack traces, raw hashes, raw lease owners, raw tokens, raw payloads, or internal paths.
