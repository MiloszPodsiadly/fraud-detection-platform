# Alert Service Failure Windows

Status: current evidence-gated regulated mutation runtime.

## Current Model

Every executable regulated mutation command declares `EVIDENCE_GATED_FINALIZE_V1`. Missing, null, retired, and unknown
persisted versions fail closed. The operator preflight reports unsupported retained records without rewriting them.

## Failure Windows

| Window | Durable outcome | Public meaning |
| --- | --- | --- |
| Created or claimed before evidence preparation | `REQUESTED` or `EVIDENCE_PREPARING` | No committed result is implied. |
| Evidence prepared before finalize | `EVIDENCE_PREPARED` | Preconditions exist; business success is not claimed. |
| Finalize outcome cannot be reconstructed safely | `FINALIZING` with recovery execution status or `FINALIZE_RECOVERY_REQUIRED` | Recovery is required; never project success from a stale snapshot. |
| Local finalize completed | `FINALIZED_EVIDENCE_PENDING_EXTERNAL` | Local mutation and required local evidence exist; external evidence remains pending. |
| External evidence confirmed | `FINALIZED_EVIDENCE_CONFIRMED` | Configured evidence policy passed; this is not legal notarization or distributed finality. |
| Evidence unavailable before finalize | `REJECTED_EVIDENCE_UNAVAILABLE` | Business mutation did not run. |
| Business validation failed | `FAILED_BUSINESS_VALIDATION` | Requested mutation was rejected. |

Outbox `PUBLISH_CONFIRMATION_UNKNOWN`, projection mismatch, and sensitive-read audit failure remain separate operational
failure modes. Publish ambiguity is not equivalent to published, and fail-closed sensitive reads return `503`.

## Invariants

- Recovery state wins over any stale response snapshot.
- No rejected or pre-finalize command may imply a committed business result.
- A stale worker cannot write after losing its owner-fenced lease.
- Replay cannot duplicate the business mutation, local success audit, or transactional outbox record.
- Local finalize does not prove external WORM storage, legal notarization, distributed ACID, or exactly-once delivery.
