# FDP-38 Merge Gate

FDP-38 is mergeable only as selected live runtime checkpoint proof using a dedicated alert-service test-fixture image. FDP-38 is not production enablement.

## Required CI

- `fdp38-live-runtime-checkpoint-chaos` must be green.
- `fdp37-production-image-chaos` must be green.
- `regulated-mutation-regression` must be green.
- Docker must be available; no Docker skip is acceptable for FDP-38.

## Required Image Evidence

- image name contains `fdp38-alert-service-test-fixture`
- image tag is `${GITHUB_SHA}`
- image id starts with `sha256:`
- digest or immutable image id is present
- `image_kind: test-fixture-production-like`
- `fixture_image: true`
- `release_image: false`
- `contains_test_classes: true`
- `contains_test_profiles: true`
- `release_candidate_allowed: false`
- `production_deployable: false`

The fixture image is not a production image, not a release image, not production deployable, and not eligible for release-candidate promotion.

## Required Proof Artifacts

- `alert-service/target/fdp38-chaos/fdp38-proof-summary.md`
- `alert-service/target/fdp38-chaos/fdp38-proof-summary.json`
- `alert-service/target/fdp38-chaos/fdp38-live-checkpoint-evidence.md`
- `alert-service/target/fdp38-chaos/fdp38-fixture-image-provenance.json`

Artifacts must contain:

- `LIVE_IN_FLIGHT_REQUEST_KILL`
- `RUNTIME_REACHED_TEST_FIXTURE`
- `checkpoint_reached=true`
- masked killed container id
- masked restarted container id
- `release_image=false`
- `contains_test_classes=true`
- `contains_test_profiles=true`
- `release_candidate_allowed=false`
- `production_deployable=false`
- `production_enablement=false`
- `runtime_reached_production_image=false`
- `precondition_setup`
- `false_success_evaluation`
- `failed_false_success_reasons=[]`
- no placeholders

## Required Tests

The CI job must fail if any required class XML is missing or skipped:

- `RegulatedMutationLiveCheckpointBeforeBusinessMutationIT`
- `RegulatedMutationLiveCheckpointAfterAttemptedAuditIT`
- `RegulatedMutationLiveCheckpointBeforeFdp29FinalizeIT`
- `RegulatedMutationLiveCheckpointBeforeSuccessAuditRetryIT`

## GO Criteria

FDP-38 is GO only if `LIVE_IN_FLIGHT_REQUEST_KILL` is non-skipped, barrier-reached, container-killed, artifact-backed, and clearly marked as `RUNTIME_REACHED_TEST_FIXTURE`.

## NO-GO Criteria

FDP-38 is NO-GO if it claims `RUNTIME_REACHED_PRODUCTION_IMAGE` while using the test-fixture image.

FDP-38 is NO-GO if the fixture Dockerfile is used by compose, FDP-37 production-image jobs, or release-image jobs. Only the `fdp38-live-runtime-checkpoint-chaos` job may build `deployment/Dockerfile.alert-service-fdp38-fixture`.

FDP-38 is NO-GO if any artifact claims `release_image=true`, `release_candidate_allowed=true`, `production_deployable=true`, production enablement, full instruction-boundary coverage, external finality, distributed ACID, Kafka exactly-once delivery, legal notarization, WORM guarantee, production certification, or bank certification.
