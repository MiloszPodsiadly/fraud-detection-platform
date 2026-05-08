# FDP-37 Final Proof Pack

FDP-37 proves production-image durable crash-window chaos readiness for regulated mutation restart safety. It builds the production-like `alert-service` Docker image from the release Dockerfile, tags it with the current commit SHA, kills the real image/container, restarts it against durable Mongo state, and verifies API/recovery/outbox/audit invariants.

FDP-37 is production-image proof, operations evidence, and a release gate. It is not production enablement, production config certification, production networking certification, or bank certification.

## Scope

FDP-31 introduced regulated mutation command/replay foundations. FDP-32 added lease-owner fenced transitions. FDP-33/FDP-34 added bounded renewal and checkpoint adoption. FDP-35 added production-readiness recovery proof. FDP-36 killed and restarted the real alert-service JVM/process. FDP-37 upgrades the required proof target to a production-like `alert-service` Docker image/container over durable crash-window states.

## Required Proof Levels

- `PRODUCTION_IMAGE_CONTAINER_KILL`
- `PRODUCTION_IMAGE_RESTART_API_PROOF`
- `DURABLE_STATE_SEEDED_CONTAINER_PROOF`
- `API_PERSISTED_STATE_PROOF`

Live in-flight production-image chaos is optional/future scope unless a separate fixture job explicitly enables a test-only checkpoint image and makes skipped execution fail CI. A skipped live in-flight test is not counted as proof.

## Required Test Evidence

| Invariant | Test class | Method | Required job | Proof levels |
| --- | --- | --- | --- | --- |
| claimed state does not falsely commit | `RegulatedMutationProductionImageChaosIT` | `productionImageKillAfterClaimBeforeAttemptedAuditDoesNotCommit` | `fdp37-production-image-chaos` | production-image durable proof |
| attempted audit state does not publish | `RegulatedMutationProductionImageChaosIT` | `productionImageKillAfterAttemptedAuditBeforeBusinessMutationDoesNotPublish` | `fdp37-production-image-chaos` | production-image durable proof |
| legacy business committing requires recovery | `RegulatedMutationProductionImageChaosIT` | `productionImageKillDuringLegacyBusinessCommittingRequiresRecoveryWithoutFalseSuccess` | `fdp37-production-image-chaos` | production-image durable proof |
| success audit pending does not duplicate business mutation | `RegulatedMutationProductionImageChaosIT` | `productionImageKillInLegacySuccessAuditPendingDoesNotRepeatBusinessMutation` | `fdp37-production-image-chaos` | production-image durable proof |
| FDP-29 finalizing does not fake external confirmation | `RegulatedMutationProductionImageChaosIT` | `productionImageKillInFdp29FinalizingDoesNotFakeExternalConfirmation` | `fdp37-production-image-chaos` | production-image durable proof |
| FDP-29 pending external remains pending | `RegulatedMutationProductionImageChaosIT` | `productionImageKillInFdp29PendingExternalRemainsPendingWithoutEvidence` | `fdp37-production-image-chaos` | production-image durable proof |
| duplicate outbox is not created after restart | `RegulatedMutationProductionImageEvidenceIntegrityIT` | `legacyReplayAfterProductionImageRestartDoesNotCreateSecondOutboxRecord` | `fdp37-production-image-chaos` | production-image durable proof |
| duplicate SUCCESS audit is not created after restart | `RegulatedMutationProductionImageEvidenceIntegrityIT` | `legacyReplayAfterProductionImageRestartDoesNotCreateSecondSuccessAudit` | `fdp37-production-image-chaos` | production-image durable proof |
| REQUIRED transaction mode has at least one production-image chaos row | `RegulatedMutationProductionImageRequiredTransactionChaosIT` | `requiredTransactionModeBusinessCommittingRestartRequiresRecoveryWithoutFalseSuccess` | `fdp37-production-image-chaos` | production-image durable proof |
| rollback artifact records no new success claims | `RegulatedMutationProductionImageRollbackIT` | `rollbackRestartKeepsFdp32FencingAndDoesNotCreateNewSuccessClaims` | `fdp37-production-image-chaos` | production-image durable proof |

## Required CI Artifact Fields

- `commit_sha`
- `image_name`
- `image_tag`
- `image_id`
- `image_digest_or_id`
- `dockerfile_path`
- `ci_job_name`
- `github_run_id`
- `network_mode`
- `network_aliases_used`
- `host_networking_used`
- `os_name`
- `ci_runner`
- `readiness_wait_strategy`
- `killed_container_id_masked`
- `restarted_container_id_masked`
- `scenario_count`
- `durable_state_seeded_scenarios_count`
- `runtime_reached_fixture_scenarios_count`
- `runtime_reached_production_image_scenarios_count`
- `final_result`
- `live_in_flight_proof_executed: false`

## State Reach Taxonomy

| Value | Meaning | May support required FDP-37 durable proof? |
| --- | --- | --- |
| `DURABLE_STATE_SEEDED` | crash-window command state was written durably before killing the production-like image | yes |
| `RUNTIME_REACHED_TEST_FIXTURE` | state was reached through a live request in a separate test fixture image | no, fixture evidence must be separate |
| `RUNTIME_REACHED_PRODUCTION_IMAGE` | state was reached live in the final production-like image | yes only if a required non-skipped live job records it |
| `FUTURE_SCOPE` | future evidence placeholder in docs only | no |

## Enablement Review Pack

`target/fdp37-chaos/fdp37-enablement-review-pack.md` and `.json` must be generated by the test harness, uploaded by CI, and validated for:

- `commit_sha`
- `image_name`
- `image_id`
- `image_digest_or_id`
- `dockerfile_path`
- `fdp37_job_status`
- `regulated_mutation_regression_status`
- `fdp35_status`
- `fdp36_status`
- `required_transaction_scenario_executed`
- `durable_state_seeded_scenario_count`
- `live_in_flight_required`
- `live_in_flight_executed`
- `rollback_validation_passed`
- `proof_artifacts_present`
- `production_enablement: false`
- `release_config_pr_required: true`
- `human_approval_required: true`
- `operator_drill_required_before_enablement: true`

The generated pack must not contain placeholders such as `TO_BE_FILLED_BY_CI`, missing local image ids, or missing local image digests in CI.

## Rollback Artifact

`target/fdp37-chaos/fdp37-rollback-validation.md` must record:

- `checkpoint_renewal_can_be_disabled_without_disabling_fencing`
- `FDP32_fencing_remains_active`
- `recovery_commands_visible_after_rollback`
- `API_returns_recovery_or_in_progress_after_rollback`
- `no_new_success_claims_after_rollback`

Rollback validation is release evidence, not production rollback approval.

## CI Job Matrix

| Job | Required | Artifact |
| --- | --- | --- |
| `fdp37-production-image-chaos` | green with zero required skips | `fdp37-production-image-chaos-reports` |
| `regulated-mutation-regression` | green | `regulated-mutation-regression-test-reports` |
| `fdp36-real-chaos` | green | `fdp36-real-chaos-test-reports` |
| `fdp35-production-readiness` | green | `fdp35-production-readiness-test-reports` |

## Limitations

FDP-37 uses a shared Testcontainers network with stable dependency aliases. It does not prove Docker Compose topology parity or production networking.

FDP-37 does not prove production environment config, production networking, production secrets, production Kafka delivery, external finality, distributed ACID, Kafka exactly-once delivery, legal notarization, or bank certification. Any such claim requires separate evidence.

Use `docs/fdp-37-enablement-decision-checklist.md`. `READY_FOR_ENABLEMENT_REVIEW` is not production enablement.
