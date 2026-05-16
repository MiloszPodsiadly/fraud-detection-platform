# FDP-38 Live Runtime Checkpoint Proof

Status: branch evidence and live checkpoint fixture proof reference.

FDP-38 proves selected live runtime checkpoint kill scenarios using the dedicated `fdp38-alert-service-test-fixture` image.

The fixture image is not a production image, not a release image, and not production enablement. FDP-38 does not claim `RUNTIME_REACHED_PRODUCTION_IMAGE`.

Some scenarios start from the initial HTTP request. `BEFORE_SUCCESS_AUDIT_RETRY` starts from a seeded durable `SUCCESS_AUDIT_PENDING` precondition and then proves the restarted fixture reached the retry checkpoint live.

| Scenario | Checkpoint | Precondition setup | State reach method | Proof level | Required invariant | Test class/method |
| --- | --- | --- | --- | --- | --- | --- |
| before legacy business mutation | `BEFORE_LEGACY_BUSINESS_MUTATION` | `LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | no false committed success, no alert decision mutation, no outbox, no SUCCESS audit | `RegulatedMutationLiveCheckpointBeforeBusinessMutationIT.beforeLegacyBusinessMutationLiveKillDoesNotCommitOrPublish` |
| after attempted audit before business mutation | `AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION` | `LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | ATTEMPTED audit count is one, no business mutation, no outbox, no SUCCESS audit | `RegulatedMutationLiveCheckpointAfterAttemptedAuditIT.afterAttemptedAuditBeforeBusinessMutationLiveKillPreservesAttemptedAuditOnly` |
| before FDP-29 local finalize | `BEFORE_FDP29_LOCAL_FINALIZE` | `LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | no finalized public status, no outbox, no local SUCCESS audit, no local finalize marker | `RegulatedMutationLiveCheckpointBeforeFdp29FinalizeIT.beforeFdp29LocalFinalizeLiveKillDoesNotClaimFinality` |
| before SUCCESS audit retry | `BEFORE_SUCCESS_AUDIT_RETRY` | `SEEDED_DURABLE_PRECONDITION_THEN_RUNTIME_REACHED_CHECKPOINT` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | business mutation remains one, outbox remains one, SUCCESS audit is not duplicated | `RegulatedMutationLiveCheckpointBeforeSuccessAuditRetryIT.beforeSuccessAuditRetryLiveKillDoesNotDuplicateBusinessMutationOrOutbox` |

All rows are required by `fdp38-live-runtime-checkpoint-chaos`. A skipped test is not proof.

## Checkpoint Semantics

- `BEFORE_LEGACY_BUSINESS_MUTATION` means after command creation, claim, and attempted audit path, before the legacy domain business mutation and outbox write.
- `AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION` means the ATTEMPTED audit was durably recorded, but business mutation, outbox, and SUCCESS audit were not executed.
- `BEFORE_FDP29_LOCAL_FINALIZE` means the FDP-29 path reached `FINALIZING`, but local finalize did not commit a visible mutation, outbox, local commit marker, or local SUCCESS audit.
- `BEFORE_SUCCESS_AUDIT_RETRY` means durable business mutation and outbox preconditions were seeded, then the runtime reached the SUCCESS audit retry checkpoint live; the proof is duplicate prevention, not absence of the earlier commit.
- Renewal checkpoint success is ownership-preservation proof, not progress proof.

## Artifact Mapping

- `fdp38-proof-summary.md/json`: aggregate proof fields and final result.
- `fdp38-live-checkpoint-evidence.md`: per-checkpoint barrier/killed/restarted evidence.
- `fdp38-fixture-image-provenance.json`: fixture image provenance.

The artifact must say `fixture_image=true`, `release_image=false`, `contains_test_classes=true`, `contains_test_profiles=true`, `release_candidate_allowed=false`, `production_deployable=false`, and `production_enablement=false`.

Required artifact fields:

- `commit_sha`
- `fixture_image_name`
- `fixture_image_id`
- `fixture_image_digest_or_id`
- `fixture_image_kind: test-fixture-production-like`
- `live_runtime_checkpoint_proof_executed: true`
- `proof_levels: LIVE_IN_FLIGHT_REQUEST_KILL`
- `state_reach_methods: RUNTIME_REACHED_TEST_FIXTURE`
- `checkpoint_count`
- `checkpoint_names`
- `precondition_setup`
- `killed_container_id_masked`
- `restarted_container_id_masked`
- `no_false_success: true`
- `failed_false_success_reasons: []`
- `no_duplicate_mutation: true`
- `no_duplicate_outbox: true`
- `no_duplicate_success_audit: true`
- `recovery_wins: true`
- `production_enablement: false`

## Checkpoint Registration Guard

Every `Fdp38LiveRuntimeCheckpoint` enum value must be represented here, in the FDP-38 CI artifact validation, and in a non-skipped test mapping. A future checkpoint must be explicitly marked `FUTURE_SCOPE`; otherwise it is required proof.

## Non-Claims

FDP-38 does not claim `RUNTIME_REACHED_PRODUCTION_IMAGE`, final production image live checkpoint proof, all crash
windows killed live, full instruction-boundary coverage, production enablement, external finality, distributed ACID,
Kafka exactly-once delivery, legal notarization, WORM guarantee, production certification, or bank certification.

The release image does not contain checkpoint barrier support.
