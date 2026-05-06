# FDP-38 Live Runtime Checkpoint Proof Matrix

FDP-38 proves selected live runtime checkpoint kill scenarios using the dedicated `fdp38-alert-service-test-fixture` image.

The fixture image is not a production image, not a release image, and not production enablement. FDP-38 does not claim `RUNTIME_REACHED_PRODUCTION_IMAGE`.

| Scenario | Checkpoint | State reach method | Proof level | Required invariant | Test class/method |
| --- | --- | --- | --- | --- | --- |
| before legacy business mutation | `BEFORE_LEGACY_BUSINESS_MUTATION` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | no false committed success, no alert decision mutation, no outbox, no SUCCESS audit | `RegulatedMutationLiveCheckpointBeforeBusinessMutationIT.beforeLegacyBusinessMutationLiveKillDoesNotCommitOrPublish` |
| after attempted audit before business mutation | `AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | ATTEMPTED audit count is one, no business mutation, no outbox, no SUCCESS audit | `RegulatedMutationLiveCheckpointAfterAttemptedAuditIT.afterAttemptedAuditBeforeBusinessMutationLiveKillPreservesAttemptedAuditOnly` |
| before FDP-29 local finalize | `BEFORE_FDP29_LOCAL_FINALIZE` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | no finalized/confirmed public status, no outbox, no local SUCCESS audit, no local finalize marker | `RegulatedMutationLiveCheckpointBeforeFdp29FinalizeIT.beforeFdp29LocalFinalizeLiveKillDoesNotClaimFinality` |
| before SUCCESS audit retry | `BEFORE_SUCCESS_AUDIT_RETRY` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | business mutation remains one, outbox remains one, SUCCESS audit is not duplicated | `RegulatedMutationLiveCheckpointBeforeSuccessAuditRetryIT.beforeSuccessAuditRetryLiveKillDoesNotDuplicateBusinessMutationOrOutbox` |

All rows are required by `fdp38-live-runtime-checkpoint-chaos`. A skipped test is not proof.

## Artifact Mapping

- `fdp38-proof-summary.md/json`: aggregate proof fields and final result.
- `fdp38-live-checkpoint-evidence.md`: per-checkpoint barrier/killed/restarted evidence.
- `fdp38-fixture-image-provenance.json`: fixture image provenance.

The artifact must say `release_image=false` and `production_enablement=false`.
