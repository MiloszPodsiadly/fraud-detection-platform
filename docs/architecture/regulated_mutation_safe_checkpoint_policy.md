# Regulated Mutation Safe Checkpoint Policy

Status: current runtime contract.

## Purpose

Safe checkpoints apply bounded lease renewal at reviewed locations in `EvidenceGatedFinalizeExecutor`. Renewal preserves
ownership, not business progress, audit completion, publication, or external finality. A failed renewal stops the
worker before it performs any later mutation or transition.

## Model Boundary

The only executable model is `EVIDENCE_GATED_FINALIZE_V1`. The durable command model version must be present and must
match the claim token. Missing, null, retired, unknown, or mismatched versions fail closed and are never interpreted as
the current model.

The read-only persisted-model preflight identifies unsupported retained records before startup. It does not rewrite,
delete, or migrate database records.

## Approved Checkpoints

| Durable state | Execution status | Approved checkpoints |
| --- | --- | --- |
| `EVIDENCE_PREPARING` | `PROCESSING` | `BEFORE_EVIDENCE_PREPARATION` |
| `EVIDENCE_PREPARED` | `PROCESSING` | `AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE`, `BEFORE_EVIDENCE_GATED_FINALIZE` |
| `FINALIZING` | `PROCESSING` | `BEFORE_EVIDENCE_GATED_FINALIZE` |

All other model, state, execution-status, and checkpoint combinations fail closed. Finalized, rejected, failed, and
recovery-required commands are not renewable.

## Failure Semantics

Checkpoint renewal validates the current owner, unexpired lease, model version, execution status, explicit state and
checkpoint pair, renewal count, and total duration budget. Rejections such as `STALE_OWNER`, `EXPIRED_LEASE`,
`MODEL_VERSION_MISMATCH`, `EXECUTION_STATUS_MISMATCH`, `NON_RENEWABLE_STATE`, `RECOVERY_STATE`, and
`BUDGET_EXCEEDED` are authoritative.

After rejection, the executor must not continue to evidence preparation, business mutation, local finalize, outbox
write, success audit, response snapshot, or another command transition. Budget exhaustion moves the command to
`FINALIZE_RECOVERY_REQUIRED` through the fenced failure path.

## Non-Claims

There is no public heartbeat endpoint, automatic scheduler, infinite renewal loop, distributed lock, distributed
ACID, exactly-once Kafka delivery, or external-finality claim. Checkpoint renewal does not change the local transaction
boundary and is not production or bank enablement by itself.

Metrics use only bounded model, checkpoint, outcome, and reason labels. They must not contain command ids, alert ids,
actor ids, lease owners, idempotency keys, request hashes, resource ids, exception messages, URLs, paths, or tokens.
