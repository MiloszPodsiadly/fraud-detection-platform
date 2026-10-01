# Regulated Mutation Lease Renewal

Status: current runtime contract.

## Decision

The regulated mutation runtime uses a bounded, owner-fenced lease renewal primitive. Renewal preserves ownership, not business progress. Lease renewal is not a guarantee of progress and must not be used as idle queue parking.

There is no public heartbeat endpoint, no automatic heartbeat scheduler, no automatic infinite renewal loop, and no distributed lock.

## Runtime Adoption Contract

`EvidenceGatedFinalizeExecutor` invokes renewal only at three reviewed safe checkpoints:

- `BEFORE_EVIDENCE_PREPARATION`
- `AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE`
- `BEFORE_EVIDENCE_GATED_FINALIZE`

Checkpoint renewal failure stops execution. No checkpoint may be inserted inside a non-idempotent external side effect.

## Renewal Caller Contract

The caller must provide the original `RegulatedMutationClaimToken`, a positive extension, and a command whose `mutation_model_version` is exactly `EVIDENCE_GATED_FINALIZE_V1`. Missing, null, retired, unknown, or mismatched model versions fail closed. They are never interpreted as the current model.

A renewal is allowed only when:

- command id and `lease_owner` match the claim token
- `lease_expires_at > now`
- `execution_status == PROCESSING`
- the current state/checkpoint pair is explicitly allowed
- the bounded renewal count and total-duration budget remain available

Missing renewal metadata is backward compatible within the current model: missing `lease_renewal_count` means `0`; missing `lease_budget_started_at` falls back to claim time, command creation time, then current time. This does not provide model-version compatibility.

## Current Renewable States

The explicit current state/checkpoint table is:

| State | Allowed checkpoint |
| --- | --- |
| `EVIDENCE_PREPARING` | `BEFORE_EVIDENCE_PREPARATION` |
| `EVIDENCE_PREPARED` | `AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE`, `BEFORE_EVIDENCE_GATED_FINALIZE` |
| `FINALIZING` | `BEFORE_EVIDENCE_GATED_FINALIZE` |

`REQUESTED`, finalized, rejected, failed, and `FINALIZE_RECOVERY_REQUIRED` states are not renewable. Recovery status wins over `responseSnapshot`; `execution_status` and recovery precedence remain authoritative.

## Budget Exhaustion

Direct budget exhaustion is durable. The current command moves to `FINALIZE_RECOVERY_REQUIRED`, execution status becomes `RECOVERY_REQUIRED`, and degradation reason is `LEASE_RENEWAL_BUDGET_EXCEEDED`. Concurrent losers must not overwrite a peer's successful renewal.

Bounded renewal cannot create infinite `PROCESSING`; exhausted budgets fail closed into durable recovery.

The primitive returns bounded reasons including `INVALID_EXTENSION`, `COMMAND_NOT_FOUND`, `MODEL_VERSION_MISMATCH`, `EXECUTION_STATUS_MISMATCH`, `STALE_OWNER`, `EXPIRED_LEASE`, `NON_RENEWABLE_STATE`, `TERMINAL_STATE`, `RECOVERY_STATE`, and `BUDGET_EXCEEDED`.

## Operations

Monitor processing duration p95/p99, commands renewing but not progressing, `Worker stuck but renewing`, and `Budget exceeded flood`. Renewal can preserve ownership but cannot prove progress.

Do not increase budget blindly. Do not bypass checkpoint renewal. Do not bypass fencing. Do not manually rewrite lease_owner. Do not manually extend expired leases. Do not mark evidence confirmed manually. Do not edit business aggregate directly. Do not submit a new idempotency key for recovery bypass. Do not disable fencing/renewal guards to clear backlog.

Metrics must not label command id, alert id, actor id, lease owner, idempotency key, request hash, resource id, exception message, raw path, or token.

This contract does not enable production or bank behavior by itself and does not provide external finality, distributed ACID, or exactly-once Kafka delivery.
