# FDP-32 Lease Fencing and Stale Worker Protection

## Problem

FDP-31 made claim acquisition atomic, but claim acquisition is not write fencing. A worker could claim a regulated mutation command, lose its lease, and later attempt to persist a post-claim state transition after another worker had taken ownership.

## Decision

All post-claim transitions are fenced by a conditional Mongo update. A transition can be persisted only when the durable command still matches:

- `commandId`
- `leaseOwner`
- `leaseExpiresAt > now`
- expected `state`
- expected `executionStatus`

Fenced update matched count `0` fails closed with `StaleRegulatedMutationLeaseException`. Stale worker writes are rejected. There is no blind retry, no last-writer-wins save, and no silent repository.save after claim.

## Components

- `RegulatedMutationClaimToken`: durable fencing token returned by successful claim acquisition.
- `RegulatedMutationFencedCommandWriter`: single component responsible for lease-owner and lease-expiry guarded transition writes.
- `StaleRegulatedMutationLeaseException`: explicit rejection for stale, expired, mismatched, or missing command transitions.

## Invariant

A worker that no longer owns the lease cannot persist command state transitions. Post-claim transitions are fenced for both legacy regulated mutation execution and the FDP-29 evidence-gated finalize executor path.

## ACID Boundary

FDP-32 does not expand transaction scope. It makes local command transitions conditional and atomic at the command-document write boundary. It does not introduce distributed locks or distributed ACID guarantees.

## Non-Goals

- no new mutation type
- no FDP-29 production enablement
- no Kafka or outbox contract change
- no external finality guarantee
- no distributed lock
- no process-kill chaos guarantee
- no heartbeat extension or lease renewal
- no public API status change

## Failure Behavior

When a fenced transition is rejected, the stale worker does not rerun the business mutation and does not persist stale response snapshots, success audit flags, outbox IDs, or recovery state. The executor reloads durable command state and returns the existing in-progress, replay, or recovery-shaped response using existing public statuses.

## Test Evidence

Coverage includes:

- claim token creation from persisted lease owner
- successful fenced transition
- stale lease owner rejection
- expired lease rejection
- expected state mismatch rejection
- expected execution status mismatch rejection
- real Mongo active-claim race
- real Mongo expired lease takeover
- stale worker rejection after takeover
- current lease owner transition success
- legacy stale `SUCCESS_AUDIT_PENDING` rejection
- evidence-gated stale `FINALIZED_EVIDENCE_PENDING_EXTERNAL` rejection
- recovery state not overwritten by stale worker
- bounded fencing metrics labels
- architecture guard for fenced writer ownership

## Known Limitations

FDP-32 does not solve process death chaos, external finality, or operator recovery procedures. A worker can still die mid-flow and require existing recovery paths. Heartbeat extension, lease renewal, process-kill chaos testing, and operational dashboards remain future work.
