# Evidence-Gated Finalize Test Plan

Status: current test plan for the feature-flagged submit-decision evidence-gated finalize path.

This plan covers the local evidence-precondition-gated finalize implementation. Expansion beyond submit-decision
cannot be accepted without tests covering this plan.

| Test Group | Scenario | Expected Invariant | Current proof |
| --- | --- | --- | --- |
| State/replay transition tests | Allowed and forbidden replay decisions across evidence-gated states | Forbidden transitions are rejected; recovery states do not become success | `EvidenceGatedFinalizeReplayPolicyTest`, `RegulatedMutationReplayPolicyRegistryTest`, `RegulatedMutationRestartRecoveryProofTest` |
| Evidence precondition tests | Missing attempted audit, transaction capability, outbox repository/recovery, recovery strategy, intent mismatch, business validation failure | No visible business mutation before required local evidence passes | `EvidencePreconditionEvaluatorTest`, `EvidenceGatedFinalizeStartupGuardTest` |
| No visible mutation before finalize tests | Evidence prep succeeds/fails before business aggregate write | Alert business fields remain unchanged until local finalize persists `FINALIZED_EVIDENCE_PENDING_EXTERNAL` | `EvidenceGatedFinalizeCoordinatorIntegrationTest`, `RegulatedMutationStaleWorkerExecutorIntegrationTest` |
| Finalize transaction rollback tests | Failure after aggregate write but before outbox/snapshot/finalize marker/success audit inside local transaction | Local Mongo transaction rolls back all local writes | `EvidenceGatedFinalizeCoordinatorIntegrationTest`, `RegulatedMutationTransactionRollbackIntegrationTest` |
| Idempotency replay tests | Same key/same payload across states; different payload; different actor; active lease | Replays state/snapshot without duplicate mutation and rejects conflicts | `EvidenceGatedFinalizeCoordinatorTest`, `EvidenceGatedFinalizeCoordinatorIntegrationTest` |
| Migration compatibility tests | Legacy commands and missing model version | Legacy replay is preserved; degraded states are not promoted | `LegacyRegulatedMutationReplayPolicyTest`, `RegulatedMutationClaimReplayPolicyCompatibilityTest`, `RegulatedMutationPublicStatusMapperTest` |
| API response contract tests | Pending, finalized, recovery, and degraded public statuses | Pending/recovery responses do not include updated resource as success; finalized responses require proof | `AlertControllerTest`, `RegulatedMutationPostRestartApiBehaviorTest`, `RegulatedMutationPublicStatusMapperTest` |
| Trust and external evidence tests | Evidence unavailable, recovery required, external pending/confirmed | Trust degrades or stays pending until evidence is proven | `MutationEvidenceConfirmationServiceTest`, `RegulatedMutationRealAlertServiceEvidenceIntegrityIT`, `RegulatedMutationProductionImageEvidenceIntegrityIT` |
| Outbox after-finalize tests | Outbox write failure, publish ambiguity, stale publish attempted | Outbox participates in the local transaction where required; broker ambiguity remains explicit | `EvidenceGatedFinalizeCoordinatorIntegrationTest`, `RegulatedMutationPostRestartApiBehaviorTest` |
| Checkpoint/fencing tests | Stale owner, expired lease, checkpoint budget exceeded | A worker cannot continue after losing ownership or checkpoint renewal | `RegulatedMutationSafeCheckpointPolicyTest`, `RegulatedMutationCheckpointRenewalExecutionTest`, `RegulatedMutationStaleWorkerExecutorIntegrationTest` |

## Future Expansion Rule

The current proof is intentionally scoped to submit-decision. New mutation families require new tests that map to this
table before they can use evidence-gated finalize semantics.

## Additional Required Checks

- Same idempotency key with different payload returns conflict.
- Same idempotency key with different actor returns conflict.
- Active lease duplicate does not execute business logic.
- Expired `FINALIZING` lease enters inspection/recovery before any rerun.
- Missing response snapshot after possible finalize does not become success.
- `FINALIZED_EVIDENCE_CONFIRMED` requires explicit evidence, not absence of errors.
- No test should require real external API calls; use fakes/Testcontainers/local adapters as appropriate.
