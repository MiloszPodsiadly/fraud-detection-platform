# API Status Truth Table

Status: current truth table for regulated mutation public statuses.

## Scope

This table is intentionally conservative. It describes what clients may infer from public statuses without
overstating finality.

| Status | Meaning | Business mutation may have happened | Audit complete | Outbox/event emitted | External confirmation exists | Retry safe | Operator action |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `REJECTED_BEFORE_MUTATION` | Rejected before business mutation. | No | No | No | No | Fix request, then new key | No |
| `IN_PROGRESS` | Another worker owns the command. | Unknown | Unknown | Unknown | No | Same key later | Only if stale |
| `RECOVERY_REQUIRED` | Durable state needs recovery inspection. | Unknown | Unknown | Unknown | No | Same key after recovery | Yes |
| `COMMIT_UNKNOWN` | Commit boundary could not be safely reported. | Unknown | Unknown | Unknown | No | Same key after recovery | Yes |
| `EVIDENCE_PREPARING` | Evidence path is preparing local evidence. | No or not visible | No | No | No | Same key later | If stalled |
| `EVIDENCE_PREPARED` | Local preconditions are staged. | No or not visible | Partial | No | No | Same key later | If stalled |
| `FINALIZING` | Local finalize is in progress. | Unknown | Unknown | Unknown | No | Same key later | If stale |
| `FINALIZED_VISIBLE` | Internal compatibility/repair state. Normal public mapping reports this as pending external evidence. | Yes | Maybe | Maybe | No | Same key only | Monitor/recover |
| `FINALIZED_EVIDENCE_PENDING_EXTERNAL` | Local visible mutation and required local evidence exist. | Yes | Local evidence present | Usually yes | No | Same key returns snapshot | Monitor external evidence |
| `FINALIZED_EVIDENCE_CONFIRMED` | Current local evidence policy is satisfied. | Yes | Yes | Yes if required | Only if configured evidence proves it | No retry required | No |
| `REJECTED_EVIDENCE_UNAVAILABLE` | Evidence was unavailable before valid finalize. | No | No | No | No | Fix cause, then new key | Possibly |
| `FAILED_BUSINESS_VALIDATION` | Business validation failed. | No | Attempt evidence only | No | No | Fix request, then new key | No |
| `FINALIZE_RECOVERY_REQUIRED` | Finalize path needs recovery. | Unknown | Unknown | Unknown | No | Same key after recovery | Yes |
| `COMMITTED_EVIDENCE_PENDING` | Legacy local commit with evidence pending. | Yes | Partial/local pending | Usually yes | No | Same key returns snapshot | Monitor evidence |
| `COMMITTED_EVIDENCE_CONFIRMED` | Legacy local evidence policy is satisfied. | Yes | Yes | Yes if required | Only if configured evidence proves it | No retry required | No |
| `COMMITTED_EVIDENCE_INCOMPLETE` | Business committed but evidence completion degraded. | Yes | No | Maybe | No | Same key returns degraded state | Yes |

## Interpretation Rules

- `checkpoint renewal` is ownership preservation, not progress.
- `recovery required` is not success.
- `finalized pending external` is not externally confirmed.
- `idempotent replay` is not distributed exactly-once processing.
- `signed release` does not prove business correctness.
