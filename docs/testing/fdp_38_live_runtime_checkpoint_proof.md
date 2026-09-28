# FDP-38 Live Runtime Checkpoint Proof

Status: current-runtime live checkpoint fixture proof reference.

FDP-38 proves selected live runtime checkpoint kill scenarios using the dedicated `fdp38-alert-service-test-fixture` image. The fixture image is not a production image, not a release image, and not production enablement. FDP-38 does not claim `RUNTIME_REACHED_PRODUCTION_IMAGE`.

Every scenario starts with `LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST`; no persisted retired-model state is seeded.

| Scenario | Checkpoint | Precondition setup | State reach method | Proof level | Required invariant | Test class/method |
| --- | --- | --- | --- | --- | --- | --- |
| before evidence preparation | `BEFORE_EVIDENCE_PREPARATION` | `LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | no business mutation, outbox, SUCCESS audit, response snapshot, or public success | `RegulatedMutationLiveCheckpointBeforeEvidencePreparationIT.killBeforeEvidencePreparationDoesNotCommitOrPublish` |
| after evidence prepared, before finalize | `AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE` | `LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | prepared evidence does not imply local commit, publication, or public success | `RegulatedMutationLiveCheckpointAfterEvidencePreparedBeforeFinalizeIT.killAfterEvidencePreparedDoesNotCommitOrPublish` |
| before evidence-gated finalize | `BEFORE_EVIDENCE_GATED_FINALIZE` | `LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST` | `RUNTIME_REACHED_TEST_FIXTURE` | `LIVE_IN_FLIGHT_REQUEST_KILL` | no finalized status, outbox, local SUCCESS audit, or local finalize marker | `RegulatedMutationLiveCheckpointBeforeEvidenceGatedFinalizeIT.killBeforeEvidenceGatedFinalizeDoesNotClaimFinality` |

All rows are required by `fdp38-live-runtime-checkpoint-chaos`. A skipped test is not proof. Renewal checkpoint success is ownership-preservation proof, not progress proof.

## Artifact Mapping

- `fdp38-proof-summary.md/json`: aggregate proof fields and final result.
- `fdp38-live-checkpoint-evidence.md`: per-checkpoint barrier, kill, and restart evidence.
- `fdp38-fixture-image-provenance.json`: fixture image provenance.

The artifact must say `fixture_image=true`, `release_image=false`, `contains_test_classes=true`, `contains_test_profiles=true`, `release_candidate_allowed=false`, `production_deployable=false`, and `production_enablement=false`.

Required artifact fields include `commit_sha`, fixture image identity, `live_runtime_checkpoint_proof_executed: true`, `proof_levels: LIVE_IN_FLIGHT_REQUEST_KILL`, `state_reach_methods: RUNTIME_REACHED_TEST_FIXTURE`, checkpoint registration, masked container identities, `no_false_success: true`, `failed_false_success_reasons: []`, duplicate-prevention results, and `recovery_wins: true`.

## Checkpoint Registration Guard

Every `Fdp38LiveRuntimeCheckpoint` enum value must be represented here, in the FDP-38 CI artifact validation, and in a non-skipped test mapping. A future checkpoint must be explicitly marked `FUTURE_SCOPE`; otherwise it is required proof.

## Non-Claims

FDP-38 does not claim final production image live checkpoint proof, all crash windows killed live, full instruction-boundary coverage, production enablement, external finality, distributed ACID, Kafka exactly-once delivery, legal notarization, WORM guarantee, production certification, or bank certification.

The release image does not contain checkpoint barrier support.
