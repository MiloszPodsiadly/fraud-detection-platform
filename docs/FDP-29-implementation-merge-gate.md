# FDP-29 Implementation Review Checklist

This checklist is for reviewing the FDP-29 local evidence-precondition-gated finalize implementation. It records evidence and limitations; it does not make a merge decision.

## Required Evidence

| Requirement | Evidence |
| --- | --- |
| Scope is submit-decision only and feature-flagged | `SubmitDecisionRegulatedMutationServiceTest`, `EvidenceGatedFinalizeStartupGuardTest` |
| Prod/bank-style startup fails closed when local finalize dependencies are missing | `EvidenceGatedFinalizeStartupGuardTest` |
| Local evidence gate has explicit version, statuses, reason codes, and checked/skipped preconditions | `EvidencePreconditionGateVersion`, `EvidencePreconditionResult`, `EvidencePreconditionEvaluatorTest` |
| Business validation happens before visible mutation | `EvidencePreconditionEvaluatorTest` |
| Real Mongo coordinator path finalizes through local transaction | `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| Success audit failure inside finalize rolls back local business/outbox/snapshot/finalize writes | `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| Outbox failure inside finalize rolls back local business/snapshot/finalize writes | `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| Retry does not duplicate business mutation when state is active, finalizing, or compatibility finalized | `EvidenceGatedFinalizeCoordinatorTest` |
| Same idempotency key with different payload or actor is rejected before business mutation | `EvidenceGatedFinalizeCoordinatorTest` |
| `FINALIZED_VISIBLE` is compatibility/repair only for new FDP-29 public semantics | `RegulatedMutationPublicStatusMapperTest`, `MutationEvidenceConfirmationServiceTest` |
| Legacy and evidence-gated evidence confirmation paths are model-version aware | `MutationEvidenceConfirmationServiceTest` |
| Metrics use bounded reason/status labels only | `AlertServiceMetricsTest` |
| Docs explicitly avoid external-finality and distributed-ACID claims | `RegulatedMutationArchitectureTest` |

## Current Implementation Boundary

FDP-29 v1 proves a local Mongo transaction path for submit-decision. The local transaction includes the alert decision write, authoritative transactional outbox record, response snapshot, local success audit write, and local finalize marker.

It does not include Kafka broker delivery, external anchor publication, remote Trust Authority signing, legal notarization, WORM storage, or independent witness finality inside that transaction.

## Expansion Prerequisites

Before expanding FDP-29 beyond submit-decision, the coordinator execution strategy should be split so shared command loading/idempotency/claiming remains separate from model-specific execution. Future executors should isolate legacy execution from `EVIDENCE_GATED_FINALIZE_V1` execution before adding more mutation types.
