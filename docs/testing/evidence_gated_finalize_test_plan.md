# Evidence-Gated Finalize Test Plan

Status: current regulated mutation runtime test plan.

| Test group | Required invariant | Current proof |
| --- | --- | --- |
| Model preflight and startup | Missing, null, retired, and unknown persisted versions fail closed without database mutation | `RegulatedMutationPersistedModelPreflightTest`, `EvidenceGatedFinalizeStartupGuardTest` |
| Replay and conflict | Recovery wins over snapshots; matching replay is idempotent; conflicting intent is rejected | `EvidenceGatedFinalizeReplayPolicyTest`, `RegulatedMutationClaimReplayPolicyTest`, `RegulatedMutationConflictPolicyTest` |
| Evidence preconditions | No visible business mutation before required local evidence is available | `EvidencePreconditionEvaluatorTest`, `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| Transaction rollback | Failure inside local finalize rolls back aggregate, outbox, audit, snapshot, and command transition | `RegulatedMutationTransactionRollbackIntegrationTest`, `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| Lease and fencing | Stale or expired owners cannot continue; checkpoint failure stops execution | `RegulatedMutationLeaseFencingIntegrationTest`, `RegulatedMutationCheckpointRenewalExecutionTest`, `RegulatedMutationStaleWorkerExecutorIntegrationTest` |
| Recovery and uncertain finalize | Ambiguous finalizing state never becomes false success; reconstructable finalized state is recovered idempotently | `RegulatedMutationRecoveryServiceTest`, `RegulatedMutationRestartRecoveryProofTest` |
| Restart and chaos | Restart preserves status truth and does not duplicate mutation, outbox, or success audit | `RegulatedMutationRealAlertServiceChaosIT`, `RegulatedMutationProductionImageChaosIT`, `RegulatedMutationProductionImageEvidenceIntegrityIT` |
| Public API | Pending and recovery responses do not expose requested state as completed | `AlertControllerTest`, `RegulatedMutationPostRestartApiBehaviorTest`, `RegulatedMutationPublicStatusMapperTest` |

Current proof remains local to the supported mutation families and configured Mongo transaction boundary. It does not
claim distributed ACID or exactly-once Kafka delivery. It does not claim WORM storage, legal notarization, or external
finality.
