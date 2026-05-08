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
- Signed release or provenance artifacts are release controls, not proof of business correctness.

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

`FINALIZED_VISIBLE` exists in the public enum for compatibility with older repair/replay paths, but the current
regulated mutation public-status mapper reports that state as `FINALIZED_EVIDENCE_PENDING_EXTERNAL` rather than a
stronger completion claim.

## Replay Behavior

Replay is safe only with the same idempotency key and the same canonical intent. Replay does not imply
distributed exactly-once processing; downstream consumers still need event-level deduplication.

## Recovery Behavior

Recovery APIs are admin/ops workflows. Recovery visibility is not business approval, external finality, or
production enablement. Sensitive recovery reads must be audited and redacted.

## Error Semantics

Public error responses should use stable fields: `code`, `message`, `correlationId`, `timestamp`, and optional
safe `details`. They must not expose stack traces, raw hashes, raw lease owners, raw tokens, or internal paths.
