# FDP-30 Regulated Mutation Executor Split

## Scope

FDP-30 is a behavior-preserving refactor of regulated mutation execution in `alert-service`.

The branch changes class boundaries only. It separates command gateway/router responsibilities from model-specific executor logic while preserving existing command state, model version names, feature flags, public API responses, Kafka contracts, recovery states, and local transaction semantics.

## Non-Goals

FDP-30 does not add:

- new business mutation type,
- FDP-29 production enablement,
- external finality,
- distributed ACID,
- exactly-once Kafka delivery,
- process-kill chaos testing,
- API contract change.

## Architecture After FDP-30

The regulated mutation path is split into these responsibilities:

- `MongoRegulatedMutationCoordinator`: command gateway/router.
- `RegulatedMutationExecutorRegistry`: fail-closed registry from mutation model version to executor.
- `LegacyRegulatedMutationExecutor`: model-specific executor for `LEGACY_REGULATED_MUTATION`.
- `EvidenceGatedFinalizeExecutor`: model-specific executor for `EVIDENCE_GATED_FINALIZE_V1`.

The coordinator remains responsible for:

- idempotency key normalization and rejection of missing keys,
- command create/load,
- request hash and backend-resolved actor conflict detection,
- canonical intent persistence on create,
- mutation model version routing through the registry.

Executors own model-specific execution. The coordinator must not execute business mutations, write phase audits, run local commit transactions, publish broker events, or implement model-specific retry/finalize algorithms.

## Production Wiring

Production Spring wiring uses:

```java
MongoRegulatedMutationCoordinator(
    RegulatedMutationCommandRepository,
    RegulatedMutationExecutorRegistry
)
```

The `RegulatedMutationExecutorRegistry` bean is the authoritative runtime routing boundary. It validates duplicate executor registrations, missing legacy executor registration, and missing FDP-29 executor registration when evidence-gated finalize is enabled.

`MongoRegulatedMutationCoordinator` still exposes compatibility constructors for focused unit tests and older test fixtures. Those constructors are not production startup/configuration safety checks and must not replace registry bean validation.

FDP-29 startup safety remains owned by `EvidenceGatedFinalizeStartupGuard`; FDP-30 registry routing does not replace transaction capability, local audit writer, outbox, recovery, or audit-chain index guards.

## Responsibility Boundaries

`MongoRegulatedMutationCoordinator` must not call:

- `command.mutation().execute(...)`,
- `transactionRunner.runLocalCommit(...)`,
- `RegulatedMutationLocalAuditPhaseWriter`,
- phase audit writers,
- Kafka publishers or outbox publishers.

`LegacyRegulatedMutationExecutor` owns legacy behavior:

- attempted audit,
- legacy local commit execution,
- success audit retry,
- post-commit audit degradation,
- legacy recovery response classification.

`EvidenceGatedFinalizeExecutor` owns FDP-29 behavior:

- evidence preparation,
- local evidence-precondition-gated finalize,
- local success audit through `RegulatedMutationLocalAuditPhaseWriter`,
- FDP-29 recovery transitions.

## Behavior-Preservation Contract

| Area | Before FDP-30 | After FDP-30 | Expected behavior | Required tests |
| --- | --- | --- | --- | --- |
| Legacy null model version | Coordinator legacy flow | `LegacyRegulatedMutationExecutor` | Legacy commands with null `mutation_model_version` route to legacy executor | `MongoRegulatedMutationCoordinatorRoutingTest`, existing legacy regulated mutation tests |
| Legacy `SUCCESS_AUDIT_PENDING` retry | Coordinator | Legacy executor | No duplicate mutation; success audit retry only | `LegacyRegulatedMutationExecutorBehaviorCompatibilityTest` |
| Legacy degraded post-commit audit | Coordinator | Legacy executor | Unchanged `COMMITTED_DEGRADED` behavior | `SubmitDecisionRegulatedMutationServiceTest`, `DecisionOutboxReconciliationServiceTest` |
| FDP-29 local finalize | `EvidenceGatedFinalizeExecutor` via coordinator branch | `EvidenceGatedFinalizeExecutor` via registry | Unchanged | `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| FDP-29 local success audit writer | Executor path | Executor path | Unchanged and FDP-29-only | `RegulatedMutationArchitectureTest`, `RegulatedMutationLocalAuditPhaseWriterIntegrationTest` |
| Executor routing | Hardcoded coordinator branching | Registry by model version | Fail closed | `RegulatedMutationExecutorRegistryTest`, `MongoRegulatedMutationCoordinatorRoutingTest` |
| Unsupported model version | Not supported | Registry failure | Fail closed; no legacy downgrade | `RegulatedMutationExecutorRegistryTest` |
| Public API statuses | Unchanged | Unchanged | No contract change | Existing controller/service tests |
| Kafka/outbox publishing | Async outbox publisher | Unchanged | No request-path publish | `RegulatedMutationArchitectureTest` |

## ACID / Transaction Boundary Statement

FDP-30 does not change local ACID boundaries.

Legacy regulated mutations keep their existing transaction behavior. Supported local commit work continues through `RegulatedMutationTransactionRunner`; success audit retry and post-commit evidence degradation remain legacy executor responsibilities.

FDP-29 remains disabled by default. When explicitly enabled, `EVIDENCE_GATED_FINALIZE_V1` still requires `transaction-mode=REQUIRED` plus the existing startup-verified transaction, outbox, recovery, local audit writer, and audit-chain index preconditions. The local finalize transaction still writes the business mutation, response snapshot, local finalize marker, transactional outbox record, and local success audit evidence through the FDP-29 local audit writer.

FDP-30 does not provide distributed ACID across MongoDB, Kafka, external witnesses, or trust-authority signatures.

## Registry Fail-Closed Behavior

The registry fails closed when:

- no legacy executor is registered,
- duplicate executors exist for one model version,
- FDP-29 evidence-gated finalize is enabled without an evidence-gated executor,
- a command resolves to a model version without a registered executor,
- an executor rejects the action/resource pair for a supported model version.

Null or missing `mutation_model_version` is explicitly legacy-compatible and routes to `LEGACY_REGULATED_MUTATION`.

## Compatibility Rules

- Existing legacy commands with null `mutation_model_version` remain valid.
- Existing `LEGACY_REGULATED_MUTATION` commands continue through the legacy executor.
- Existing `EVIDENCE_GATED_FINALIZE_V1` commands continue through the evidence-gated executor.
- Feature flags remain disabled by default.
- The synchronous API does not gain new statuses or stronger finality claims.

## Validation Checklist

- Full legacy regulated mutation tests green.
- FDP-29 integration tests green.
- Registry tests green.
- Architecture tests green.
- Feature flags remain disabled by default.
- Coordinator does not execute model-specific mutation logic.
- Local audit writer remains FDP-29-only.

## Future Work / Not In This Branch

Future extraction candidates:

- `RegulatedMutationClaimService`,
- `RegulatedMutationConflictPolicy`,
- `RegulatedMutationReplayResolver`,
- `RegulatedMutationRecoveryClassifier`.

FDP-30 intentionally does not extract these to avoid a larger behavioral diff. Claim and replay logic can remain duplicated between legacy and evidence-gated executors where model-specific semantics differ.

Any future extraction must include regression tests for:

- `SUCCESS_AUDIT_PENDING` retry,
- recovery-required precedence,
- idempotency conflict,
- active lease duplicate,
- expired lease recovery.
