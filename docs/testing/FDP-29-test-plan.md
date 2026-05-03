# FDP-29 Implementation Test Plan

FDP-29 is a design contract plus a feature-flagged submit-decision implementation prototype. Expansion beyond submit-decision cannot be accepted without tests covering this plan.

| Test Group | Scenario | Expected Invariant | Integration Required? | Future Test Class Proposal |
| --- | --- | --- | --- | --- |
| State machine transition tests | Every allowed and forbidden transition across future states | Forbidden transitions are rejected; allowed transitions are deterministic | Unit | `EvidenceGatedFinalizeStateMachineTest` |
| Evidence precondition tests | Missing attempted audit, transaction capability, outbox write, signing, external anchor policy | No visible business mutation before required evidence passes | Unit + targeted integration | `EvidencePreconditionGateTest` |
| No visible mutation before finalize tests | Evidence prep succeeds/fails before business aggregate write | Alert/case business fields remain unchanged until local finalize persists `FINALIZED_EVIDENCE_PENDING_EXTERNAL` | Integration | `NoVisibleMutationBeforeFinalizeIntegrationTest` |
| Finalize transaction rollback tests | Failure after aggregate write but before outbox/snapshot/finalize marker inside local transaction | Local Mongo transaction rolls back all local writes | Mongo integration | `EvidenceGatedFinalizeTransactionRollbackTest` |
| Idempotency replay tests | Same key/same payload across every state | Replays state/snapshot without duplicate mutation | Unit + integration | `EvidenceGatedIdempotencyReplayTest` |
| Migration compatibility tests | Legacy commands with FDP-25/FDP-26/FDP-27/FDP-28 statuses | Legacy replay preserved; degraded states not promoted | Unit | `EvidenceGatedLegacyCompatibilityTest` |
| API response contract tests | Each future status response | Pending/recovery responses do not include updated resource; finalized responses do | Controller/API | `EvidenceGatedApiContractTest` |
| Trust-level impact tests | Evidence unavailable, recovery required, external pending/confirmed | Trust degrades or stays pending until evidence is proven | Unit | `EvidenceGatedTrustLevelImpactTest` |
| Outbox after-finalize tests | Outbox write failure, publish ambiguity, stale publish attempted | Outbox is local transaction precondition where required; broker ambiguity remains explicit | Integration + unit | `EvidenceGatedOutboxFinalizeTest` |
| External evidence after-finalize tests | External witness unavailable or confirmation fails after visible finalize | Status remains pending/degraded; never false confirmed | Unit + adapter fake | `EvidenceGatedExternalEvidenceTest` |
| Legacy command replay tests | Existing command has no model version or legacy state | Uses legacy response semantics safely | Unit | `LegacyRegulatedMutationReplayTest` |

## Additional Required Checks

- Same idempotency key with different payload returns conflict.
- Same idempotency key with different actor returns conflict.
- Active lease duplicate does not execute business logic.
- Expired `FINALIZING` lease enters inspection/recovery before any rerun.
- Missing response snapshot after possible finalize does not become success.
- `FINALIZED_EVIDENCE_CONFIRMED` requires explicit evidence, not absence of errors.
- No test should require real external API calls; use fakes/Testcontainers/local adapters as appropriate.
