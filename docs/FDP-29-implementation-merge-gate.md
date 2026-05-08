# FDP-29 Implementation Review Checklist

This checklist is for reviewing the FDP-29 local evidence-precondition-gated finalize implementation. It records evidence and limitations; it does not make a merge decision.

## Merge Gate

FDP-29 may merge as a disabled-by-default prototype when:

- feature flags remain disabled by default
- startup guard fails closed when local finalize dependencies are missing
- local Mongo coordinator-path tests pass
- rollback corruption test passes
- local evidence-precondition wording is honest
- no external-finality claim is made
- no distributed ACID or exactly-once claim is made

Merge does not equal production or bank enablement approval.

## Required Evidence

| Requirement | Evidence |
| --- | --- |
| Scope is submit-decision only and feature-flagged | `SubmitDecisionRegulatedMutationServiceTest`, `EvidenceGatedFinalizeStartupGuardTest` |
| Prod/bank-style startup fails closed when local finalize dependencies are missing | `EvidenceGatedFinalizeStartupGuardTest` |
| Local evidence gate has explicit version, statuses, reason codes, and checked/skipped preconditions | `EvidencePreconditionGateVersion`, `EvidencePreconditionResult`, `EvidencePreconditionEvaluatorTest` |
| Business validation happens before visible mutation | `EvidencePreconditionEvaluatorTest` |
| Real Mongo coordinator path finalizes through local transaction | `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| Generic `AuditService` fanout is not used inside FDP-29 finalize transaction | `RegulatedMutationArchitectureTest` |
| Local `SUCCESS` audit writer is idempotent and not a second audit source of truth | `RegulatedMutationLocalAuditPhaseWriterIntegrationTest`, `RegulatedMutationArchitectureTest` |
| Concurrent FDP-29 finalizations do not fork the local audit chain or duplicate chain positions | `EvidenceGatedFinalizeCoordinatorIntegrationTest`, `RegulatedMutationLocalAuditPhaseWriterIntegrationTest` |
| Local audit writer retry/backoff is configurable, bounded, and guarded at startup | `LocalAuditPhaseWriterProperties`, `EvidenceGatedFinalizeStartupGuardTest` |
| Local audit writer contention, retries, lock release failures, and append duration are observable with low-cardinality metrics | `AlertServiceMetricsTest`, `RegulatedMutationLocalAuditPhaseWriterIntegrationTest` |
| Stale audit-chain locks expire and retry exhaustion is explicit | `RegulatedMutationLocalAuditPhaseWriterIntegrationTest` |
| Success audit failure inside finalize rolls back local business/outbox/snapshot/finalize writes | `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| Assigned finalize fields are not re-persisted after transaction rollback | `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| Outbox failure inside finalize rolls back local business/snapshot/finalize writes | `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| Missing outbox after local commit is recovery-required, not normal pending publication | `MutationEvidenceConfirmationServiceTest` |
| Recovery-required replay never returns stale committed-looking response snapshots | `EvidenceGatedFinalizeCoordinatorTest` |
| Retry does not duplicate business mutation when state is active, finalizing, or compatibility finalized | `EvidenceGatedFinalizeCoordinatorTest` |
| Same idempotency key with different payload or actor is rejected before business mutation | `EvidenceGatedFinalizeCoordinatorTest` |
| `FINALIZED_VISIBLE` is compatibility/repair only for new FDP-29 public semantics | `RegulatedMutationPublicStatusMapperTest`, `MutationEvidenceConfirmationServiceTest` |
| Legacy and evidence-gated evidence confirmation paths are model-version aware | `MutationEvidenceConfirmationServiceTest` |
| Evidence confirmation limit `<=0` is explicit no-op | `MutationEvidenceConfirmationServiceTest` |
| Metrics use bounded reason/status labels only | `AlertServiceMetricsTest` |
| `FINALIZING_RETRY_REQUIRES_RECONCILIATION` has operator runbook coverage | `docs/runbooks/fdp-29-finalize-recovery-required.md` |
| Local audit-chain contention has operator runbook coverage | `docs/runbooks/fdp-29-local-audit-chain-contention.md` |
| FDP-29 evidence-gated execution is isolated from the legacy coordinator flow | `EvidenceGatedFinalizeExecutor`, `RegulatedMutationArchitectureTest` |
| Docs explicitly avoid external-finality and distributed-ACID claims | `RegulatedMutationArchitectureTest` |

## Current Implementation Boundary

FDP-29 v1 proves a local Mongo transaction path for submit-decision. The local transaction includes the alert decision write, authoritative transactional outbox record, response snapshot, local success audit write through `RegulatedMutationLocalAuditPhaseWriter`, and local finalize marker.

It does not include Kafka broker delivery, external anchor publication, remote Trust Authority signing, legal notarization, WORM storage, or independent witness finality inside that transaction.

`RegulatedMutationLocalAuditPhaseWriter` writes to the same authoritative `audit_events` collection and local anchor collection. It is not a second source of truth. It is intentionally limited to the FDP-29 finalize transaction and does not call `AuditService`, `AuditEventPublisher`, Kafka, external anchor publishers, or Trust Authority clients.

`EvidenceGatedFinalizeExecutor` owns the FDP-29 model-specific evidence preparation and finalize transaction. `MongoRegulatedMutationCoordinator` remains responsible for shared command creation/loading and routing by mutation model version. This keeps additional mutation models from accumulating finalize-specific branches in the coordinator.

## Production/Bank Enablement Gate

Before setting:

- `app.regulated-mutations.evidence-gated-finalize.enabled=true`
- `app.regulated-mutations.evidence-gated-finalize.submit-decision.enabled=true`

operators must have:

- explicit config approval
- `app.regulated-mutations.transaction-mode=REQUIRED`
- transaction probe green
- outbox recovery enabled
- `app.audit.local-phase-writer.max-append-attempts` greater than `0`
- `app.audit.local-phase-writer.backoff-ms` greater than `0`
- `app.audit.local-phase-writer.max-total-wait-ms` greater than `0` and at most `5000` unless explicitly approved
- required unique indexes present on `audit_events` and `audit_chain_anchors`
- local audit writer inside transaction
- dashboards or alerts for `fdp29_local_audit_chain_append_total`, `fdp29_local_audit_chain_retry_total`, `fdp29_local_audit_chain_append_duration_ms`, and `fdp29_local_audit_chain_lock_release_failure_total`
- external audit publication post-commit only
- `FINALIZING` recovery runbook reviewed
- local audit-chain contention runbook reviewed
- evidence confirmation tests green
- rollback corruption test green
- controller/API response test green
- canary or staging soak result
- rollback plan to disable both feature flags

FDP-29 merge does not approve enabling these flags in production or bank mode.

## Expansion Prerequisites

Before expanding FDP-29 beyond submit-decision, split the execution strategy so shared command loading/idempotency/claiming remains separate from model-specific execution:

- `RegulatedMutationCommandGateway` or coordinator: create/load command, idempotency conflict, claim/lease, shared inspection
- `LegacyRegulatedMutationExecutor`: existing FDP-25/FDP-26 flow
- `EvidenceGatedFinalizeExecutor`: FDP-29 local evidence-precondition-gated flow implemented for submit-decision
- `EvidencePreconditionEvaluator`: policy/gate only
- `RegulatedMutationPublicStatusMapper`: projections only
- `MutationEvidenceConfirmationService`: post-finalize evidence confirmation

No more mutation model branching should be added inside `MongoRegulatedMutationCoordinator` before enabling this model for another mutation type.
