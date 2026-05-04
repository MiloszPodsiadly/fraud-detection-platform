# FDP-31 Regulated Mutation Claim And Replay Policy Extraction

## Scope

FDP-31 is a behavior-preserving refactor of regulated mutation command lifecycle policy in `alert-service`.

It extracts shared claim, conflict, lease and replay decision policy used by:

- `LegacyRegulatedMutationExecutor`
- `EvidenceGatedFinalizeExecutor`

Implemented classes:

- `RegulatedMutationClaimService`
- `RegulatedMutationConflictPolicy`
- `RegulatedMutationLeasePolicy`
- `RegulatedMutationReplayResolver`
- `RegulatedMutationReplayDecision`
- `RegulatedMutationReplayDecisionType`

## Non-Goals

FDP-31 makes no public API status changes.
FDP-31 does not change public API statuses.
FDP-31 does not change mutation states.
FDP-31 makes no transaction boundary changes.
FDP-31 does not change transaction boundaries.
FDP-31 does not change legacy audit timing.
FDP-31 does not change FDP-29 local finalize audit timing.
FDP-31 does not enable FDP-29.
FDP-29 remains disabled by default.
FDP-31 does not change Kafka/outbox semantics.
FDP-31 does not add external finality.
FDP-31 does not add a new mutation type.

## Current Problem

After FDP-30, regulated mutation execution is split by model version, but the executors still duplicated command lifecycle policy:

- claim and lease acquisition,
- active duplicate handling,
- idempotency conflict checks,
- snapshot replay eligibility,
- recovery-required precedence,
- terminal and concurrent response decisions.

Duplicated lifecycle policy makes future executor additions risky because recovery and replay precedence can drift between implementations.

## Target Architecture

`MongoRegulatedMutationCoordinator` remains the command gateway and model-version router.

Executors remain responsible for model-specific execution:

- legacy attempted audit, business mutation, SUCCESS audit retry and post-commit degradation handling stay in `LegacyRegulatedMutationExecutor`,
- FDP-29 evidence preparation, local finalize, local SUCCESS audit and repair transitions stay in `EvidenceGatedFinalizeExecutor`.

Shared lifecycle policy is now owned by dedicated components:

| Component | Responsibility |
| --- | --- |
| `RegulatedMutationConflictPolicy` | Validate same idempotency key against request hash and backend-resolved actor identity. |
| `RegulatedMutationClaimService` | Perform the Mongo `findAndModify` claim/lease update. |
| `RegulatedMutationLeasePolicy` | Classify active and expired `PROCESSING` leases. |
| `RegulatedMutationReplayResolver` | Decide replay, recovery, rejected/status and active duplicate outcomes without writes. |

Future model executors must not implement custom claim/replay logic unless the reason is explicit and covered by regression tests.

## Responsibility Boundaries

Policy components must not execute business mutation, write phase audit, write outbox records, publish Kafka, call external anchors, call transaction runners, or shape public API contracts.

Executors may interpret replay decisions into model-specific transitions and responses. For example, `FINALIZED_VISIBLE` repair remains an FDP-29 executor transition because it records FDP-29 metrics and uses the FDP-29 state machine.

## Behavior-Preservation Contract

| Behavior | Preserved result | Owner |
| --- | --- | --- |
| Same key, different request hash | `ConflictingIdempotencyKeyException` | `RegulatedMutationConflictPolicy` |
| Same key, different actor | `ConflictingIdempotencyKeyException` | `RegulatedMutationConflictPolicy` |
| Null legacy `intentActorId` | Legacy-compatible non-conflict | `RegulatedMutationConflictPolicy` |
| `NEW` command claim | `PROCESSING` lease acquired | `RegulatedMutationClaimService` |
| Active `PROCESSING` lease | No claim stealing; status response | `RegulatedMutationLeasePolicy`, `RegulatedMutationReplayResolver` |
| Expired `PROCESSING` lease | Claimable, unless unsafe state requires recovery | `RegulatedMutationClaimService`, `RegulatedMutationReplayResolver` |
| Legacy `SUCCESS_AUDIT_PENDING` with snapshot | SUCCESS audit retry only; no business rerun | `RegulatedMutationReplayResolver`, legacy executor |
| Legacy `RECOVERY_REQUIRED` with snapshot | Recovery wins; no committed replay | `RegulatedMutationReplayResolver` |
| FDP-29 `FINALIZE_RECOVERY_REQUIRED` with snapshot | Recovery wins; no finalized replay | `RegulatedMutationReplayResolver` |
| FDP-29 `FINALIZED_VISIBLE` with proof | Repair to pending external evidence | `RegulatedMutationReplayResolver`, evidence executor |
| FDP-29 `FINALIZED_VISIBLE` without proof | Recovery required | `RegulatedMutationReplayResolver`, evidence executor |

RECOVERY_REQUIRED must win over responseSnapshot replay.
FINALIZE_RECOVERY_REQUIRED must win over responseSnapshot replay.
Rejected terminal states must win over responseSnapshot replay.

## ACID / Transaction Boundary Statement

FDP-31 does not change local ACID behavior.

Legacy mutation execution still uses the configured `RegulatedMutationTransactionRunner` exactly where it did before this branch.
FDP-29 local finalize still requires local transaction mode and still performs local SUCCESS audit timing inside its existing finalize path.

No policy component creates a wider transaction boundary or claims distributed ACID behavior.

## Fail-Closed Rules

- Unsupported model/action/resource routing remains fail-closed in `RegulatedMutationExecutorRegistry`.
- Idempotency conflicts throw before mutation or audit execution.
- Active leases are not stolen.
- Unsafe expired states return recovery/status decisions instead of rerunning business mutation.
- Missing command after claim miss remains an explicit missing-idempotency failure.

## Known Limitation: Claim Acquisition Is Not Write Fencing

FDP-31 centralizes claim acquisition.
FDP-31 does not implement lease-owner write fencing.

Claim acquisition is atomic through Mongo `findAndModify`, including the idempotency key, request hash and claimable execution status conditions.
Later command transition writes remain behavior-preserving and are not changed by FDP-31.

Therefore, FDP-31 must not be described as solving stale worker write protection.
This branch does not add fenced transition updates by `commandId`, `leaseOwner` and unexpired lease.

## FDP-32 - Regulated Mutation Lease Fencing & Stale Worker Protection

FDP-32 is the future scope for full stale-worker write protection.

Expected FDP-32 goals:

- fenced command transitions,
- stale worker write rejection,
- transition update by `commandId`, `leaseOwner` and unexpired lease,
- real Mongo concurrency tests,
- metrics for stale write attempts,
- no business rerun after lease takeover.

## Test Matrix

| Test | Coverage |
| --- | --- |
| `RegulatedMutationConflictPolicyTest` | Hash/actor conflict extraction and legacy null actor compatibility. |
| `RegulatedMutationClaimServiceTest` | Claim query, lease updates, heartbeat/update timestamp, attempt count, no audit/mutation path. |
| `RegulatedMutationLeasePolicyTest` | Null/past/exact/future lease expiration and active processing classification. |
| `RegulatedMutationReplayResolverTest` | Legacy and FDP-29 replay/recovery precedence. |
| `RegulatedMutationClaimReplayPolicyCompatibilityTest` | Golden behavior-preservation matrix for claim/replay/conflict routing. |
| `LegacyRegulatedMutationExecutorBehaviorCompatibilityTest` | Legacy executor behavior after extraction. |
| `EvidenceGatedFinalizeCoordinatorTest` | FDP-29 executor behavior after extraction. |
| `RegulatedMutationArchitectureTest` | Boundary guards for FDP-31 policy ownership, no overclaiming and project documentation wording. |

## Risk

The primary risk is accidentally changing terminal response precedence when moving conditions out of executors.

The mitigation is direct resolver tests plus golden compatibility tests for recovery precedence, active leases, expired unsafe states, snapshot replay and unsupported routing.

## Future Work / Not In This Branch

- Add new regulated mutation model executors only after defining explicit action/resource support and regression coverage.
- Consider a separate concurrent response service only if executor-specific response mapping grows further.
- Keep FDP-29 production enablement controlled by its existing startup guards and feature flags.
- Implement lease-owner transition fencing in FDP-32.
