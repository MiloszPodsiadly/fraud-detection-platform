# FDP-29 Compatibility Matrix

FDP-29 does not rename historical command states in place. Existing statuses remain accepted for legacy replay and inspection.

| Existing Status | Current Meaning | Future Equivalent | Deprecated? | Legacy Replay Accepted? | API Compatibility Rule | Migration Note |
| --- | --- | --- | --- | --- | --- | --- |
| `REJECTED_BEFORE_MUTATION` | Command rejected before visible business write. | `REJECTED_EVIDENCE_UNAVAILABLE` or `FAILED_BUSINESS_VALIDATION` by reason. | No | Yes | Keep current body; do not include updated resource. | Preserve reason code and map to future rejection class. |
| `COMMITTED_AUDIT_PENDING` | Business committed; audit completion pending. | Legacy-only `FINALIZED_VISIBLE` with evidence pending/degraded. | Yes for new model | Yes | Do not present as evidence confirmed. | New model should avoid this by gating before finalize. |
| `COMMITTED_AUDIT_INCOMPLETE` | Business committed; audit evidence incomplete. | Legacy degraded finalized state. | Yes for new model | Yes | Return degraded/incomplete status, not confirmed. | Requires trust degradation and recovery evidence. |
| `COMMITTED_EVIDENCE_PENDING` | Local commit complete; external evidence pending. | `FINALIZED_EVIDENCE_PENDING_EXTERNAL`. | No | Yes | Committed resource may be returned with pending evidence status. | Natural compatibility state. |
| `COMMITTED_EVIDENCE_INCOMPLETE` | Local commit occurred; evidence completion degraded. | `FINALIZE_RECOVERY_REQUIRED` or finalized degraded compatibility state. | Yes for new model | Yes | Do not claim confirmation; may require operator action. | New model should reduce this to external-only gaps. |
| `COMMITTED_FULLY_ANCHORED` | Stronger committed/anchored status, intentionally not returned synchronously. | `FINALIZED_EVIDENCE_CONFIRMED` only if evidence policy truly satisfied. | Yes as sync response | Yes | Never infer from local-only evidence. | Map only after external evidence is verified. |
| `COMMITTED_DEGRADED` | Business committed with post-commit degradation. | `FINALIZE_RECOVERY_REQUIRED` or finalized degraded compatibility state. | Yes for new model | Yes | Expose degraded status and trust impact. | Must not be normalized to success. |
| `RECOVERY_REQUIRED` | Manual or bounded recovery required. | `FINALIZE_RECOVERY_REQUIRED`. | No | Yes | HTTP 202 or inspection status; no updated resource as success. | Future recovery should prove finalization before returning committed snapshot. |
| `COMMIT_UNKNOWN` | Commit outcome cannot be safely reported. | `COMMIT_UNKNOWN` / `FINALIZE_RECOVERY_REQUIRED`. | No | Yes | No updated resource; operator/retry guidance required. | Use when local transaction outcome cannot be proven. |
| `PUBLISH_CONFIRMATION_UNKNOWN` | Kafka publish may have happened but durable local confirmation failed. | Post-finalize outbox ambiguity. | No | Yes | Do not retry automatically as normal unpublished; do not claim exactly-once. | Remains outside local finalize transaction. |
| `EVIDENCE_CONFIRMED` | Local evidence confirmation complete according to current policy. | `FINALIZED_EVIDENCE_CONFIRMED` if policy matches future preconditions. | No | Yes | Only expose as confirmed when source evidence is still valid. | Migration must re-evaluate evidence policy version. |
| `LOCAL_STATUS_UNVERIFIED` | Local external publication status cannot be trusted. | Pending/degraded external evidence. | No | Yes | No healthy/confirmed trust claim. | Reconcile external status before promotion. |
| `FAILED_TERMINAL` | Terminal failure, no automatic retry. | Terminal failed command or unrecoverable finalize failure. | No | Yes | No updated resource as success. | Requires explicit operator handling. |
| `PENDING` | Command/outbox work not completed. | `PENDING_EVIDENCE` / `EVIDENCE_PREPARING`. | No | Yes | Return accepted/pending response only. | Determine future state from command phase. |
| `PROCESSING` | Active lease or worker processing. | `EVIDENCE_PREPARING` or `FINALIZING`. | No | Yes | Same key should return in-progress; no duplicate execution. | Lease ownership remains authoritative. |

## Compatibility Rules

- Legacy commands keep their original state names in storage unless a migration explicitly updates them.
- APIs may map legacy states to future response statuses but must not change historical meaning.
- `COMMITTED_*` legacy states do not prove future evidence-gated finalization unless the command also has the required future evidence markers.
- `PUBLISH_CONFIRMATION_UNKNOWN` is never equivalent to `FINALIZED_EVIDENCE_CONFIRMED`.
