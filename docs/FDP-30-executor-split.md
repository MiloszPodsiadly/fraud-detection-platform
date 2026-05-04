# FDP-30 Regulated Mutation Executor Split

FDP-30 is a behavior-preserving refactor of regulated mutation execution in `alert-service`.

## Why It Exists

Before FDP-30, `MongoRegulatedMutationCoordinator` mixed command gateway responsibilities with legacy regulated mutation execution. FDP-29 already isolated its evidence-gated finalize path in `EvidenceGatedFinalizeExecutor`, but the legacy path still lived inside the coordinator.

That made the coordinator harder to reason about because it both created or loaded command records and executed model-specific mutation algorithms.

## Architecture

The regulated mutation path is now split into four responsibilities:

- `MongoRegulatedMutationCoordinator`: command gateway and router.
- `RegulatedMutationExecutorRegistry`: startup-validated mapping from mutation model version to executor.
- `LegacyRegulatedMutationExecutor`: `LEGACY_REGULATED_MUTATION` execution flow.
- `EvidenceGatedFinalizeExecutor`: `EVIDENCE_GATED_FINALIZE_V1` execution flow.

The coordinator remains responsible for:

- idempotency key normalization and rejection of missing keys,
- command create/load,
- request hash and backend-resolved actor conflict detection,
- canonical intent persistence on create,
- mutation model version routing through the registry.

Executors own model-specific execution. The coordinator must not execute business mutations, write phase audits, run local commit transactions, or implement model-specific retry/finalize algorithms.

## ACID Preservation

FDP-30 does not change FDP-26 or FDP-29 transaction semantics.

Legacy regulated mutation behavior remains unchanged:

- attempted audit is written before business mutation,
- supported local commit work continues through `RegulatedMutationTransactionRunner`,
- success audit retry and `COMMITTED_DEGRADED` behavior remain legacy executor responsibilities.

FDP-29 behavior remains unchanged:

- `EVIDENCE_GATED_FINALIZE_V1` remains disabled by default,
- local finalize still requires `transaction-mode=REQUIRED`,
- local success audit uses `RegulatedMutationLocalAuditPhaseWriter`,
- response snapshot, local commit marker, and transactional outbox remain in the local finalize transaction.

## Non-Goals

FDP-30 does not add:

- new business behavior,
- a new mutation type,
- production enablement of FDP-29,
- external finality,
- distributed ACID,
- exactly-once Kafka delivery,
- process-kill chaos testing.

## Migration Risk

The intended risk profile is class-boundary-only. Existing command state, model version names, feature flags, public API responses, Kafka contracts, and recovery states remain unchanged.

The registry fails closed if:

- no legacy executor is registered,
- duplicate executors exist for one model version,
- FDP-29 evidence-gated finalize is enabled without an evidence-gated executor,
- a command resolves to a model version without a registered executor.

## Validation Checklist

The FDP-30 validation scope is:

- coordinator does not call `command.mutation().execute`,
- coordinator does not call `transactionRunner.runLocalCommit`,
- coordinator does not use `RegulatedMutationLocalAuditPhaseWriter`,
- coordinator does not write phase audits,
- executor registry tests cover missing and duplicate executors,
- FDP-29 coordinator integration tests still pass,
- legacy submit-decision tests still pass,
- feature flags remain unchanged,
- local audit writer remains FDP-29-only.
