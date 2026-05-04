# FDP-32 Lease Fencing and Stale Worker Protection

## Problem

FDP-31 made claim acquisition atomic, but claim acquisition is not write fencing. A worker can claim a regulated mutation command, lose its lease, and later attempt to continue the command after another worker has taken ownership.

## Decision

FDP-32 adds command transition fencing and active lease validation before local business mutation execution. Post-claim transitions and recovery transitions are persisted only through conditional Mongo updates with explicit expected-state guards.

## Scope

The scope is limited to regulated mutation execution inside `alert-service`. FDP-32 does not change public API statuses, Kafka contracts, scoring behavior, outbox payload contracts, or FDP-29 production enablement.

## Components

- `RegulatedMutationClaimToken`: durable claim token containing command id, lease owner, lease expiry, mutation model version, and expected execution state.
- `RegulatedMutationFencedCommandWriter`: shared writer for fenced post-claim transitions, active lease validation, and conditional recovery transitions.
- `StaleRegulatedMutationLeaseException`: explicit stale lease rejection for owner, expiry, state, status, or missing-command mismatch.
- `RegulatedMutationRecoveryWriteConflictException`: explicit rejection when a non-claimed recovery transition no longer matches durable command state.

## Command Transition Fencing Invariant

All post-claim transitions are fenced by a conditional Mongo update. A claimed transition can persist only when the durable command still matches:

- `commandId`
- `leaseOwner`
- `leaseExpiresAt > now`
- expected `state`
- expected `executionStatus`
- expected `mutationModelVersion`

Stale workers cannot persist command transitions after losing lease ownership. Recovery repair is also conditional and cannot blind-save over another active worker. There is no silent repository.save after claim.

## Business Mutation Safety Boundary

FDP-32 validates the active lease immediately before local business mutation execution. This closes the stale business mutation gap for the normal executor path.

command transition fencing is not business-side-effect rollback by itself. transaction-mode OFF is compatibility behavior and must not be described as bank-grade stale-worker-safe. transaction-mode REQUIRED is required for bank-grade stale-worker business-write safety unless a mutation-specific idempotent domain write guard is explicitly approved.

## ACID Boundary

FDP-32 does not expand transaction scope. In bank or production mode, the stale-worker business-write guarantee depends on transaction-mode REQUIRED. Local/dev transaction-mode OFF remains useful for compatibility and quickstart paths, but it is not a production stale-worker rollback guarantee and must not be used for bank-grade regulated mutation paths.

## Non-Goals

- no distributed lock
- no process-kill chaos proof
- no heartbeat extension or lease renewal
- no public API status change
- no Kafka/outbox contract change
- no external finality guarantee
- no FDP-29 production/bank enablement
- no claim that transaction-mode OFF prevents all business side effects

## Failure Behavior

When active lease validation fails, the executor does not execute the business mutation. It does not persist response snapshots, outbox IDs, local commit markers, or success-audit flags from the stale worker. The executor reloads durable command state and returns the existing in-progress, replay, or recovery-shaped response using existing public statuses.

When conditional recovery persistence fails, the recovery write fails closed with an explicit recovery write conflict instead of overwriting current durable state.

## Observability

FDP-32 records low-cardinality metrics for:

- fenced transition outcome
- stale write rejection reason
- lease takeover rate
- lease remaining at transition
- transition latency
- recovery write conflicts
- lease budget warnings when the remaining lease budget is at or below the configured threshold

Metric labels are bounded to model version, state, transition outcome, and reason. They must not contain command ids, alert ids, actor ids, idempotency keys, lease owners, request hashes, exception messages, or raw paths.

Recommended dashboards should track stale write rejection rate, expired lease rejection rate, lease takeover rate, transition latency p95/p99, lease remaining p50/p95/p99, and recovery write conflict count.

Recommended alerts should cover stale write spikes, expired lease spikes, lease remaining below threshold, and any non-zero recovery write conflict count.

## Test Evidence

Coverage includes:

- claim token creation from persisted lease owner
- successful fenced transition
- active lease validation query
- stale lease owner rejection
- expired lease rejection
- expected state mismatch rejection
- expected execution status mismatch rejection
- conditional recovery write conflict
- real Mongo active-claim race
- real Mongo expired lease takeover
- stale worker rejection after takeover
- executor-path stale worker prevention before legacy mutation execution
- executor-path stale worker prevention before FDP-29 finalize mutation execution
- current lease owner execution success
- response snapshot, outbox id, local commit marker, and success audit not persisted after pre-mutation lease rejection
- bounded fencing metrics labels
- architecture guard preventing executor `commandRepository.save(...)` transition backdoors

## Production/Bank Enablement Conditions

Production or bank enablement requires transaction-mode REQUIRED, bank-mode startup guards, active recovery runbooks, lease duration budget review, dashboards and alerts for the FDP-32 metrics, canary or staging soak, rollback planning, and separate release approval for enabling FDP-29 behavior. FDP-32 can harden the disabled/default path, but it does not by itself enable evidence-gated finalize for production.

## Known Limitations

FDP-32 does not provide distributed ACID, process-kill chaos proof, heartbeat renewal, or external finality. Source-string architecture tests are guardrails, not complete architectural proof; the critical stale-worker behavior is also covered by integration tests. A worker can still die mid-flow and require existing recovery paths. Full process-failure hardening remains a later operational and chaos-testing concern.

`allowedFieldUpdates` is not a general document mutation API. It may only set transition-owned evidence and projection fields such as response snapshots, outbox event ids, local commit markers, success audit ids, degradation reasons, and public status. Identity, lease, ownership, idempotency, request, resource, action, creation, attempt-count, and mutation-model fields are immutable after command creation or claim.
