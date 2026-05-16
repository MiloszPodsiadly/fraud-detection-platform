# FDP-37 Merge Gate

Status: branch evidence.


FDP-37 is mergeable only as production-image durable crash-window chaos proof and release-gate evidence. It is not production enablement.

## Required CI

- `fdp37-production-image-chaos` must be green on `ubuntu-latest`.
- `regulated-mutation-regression` must be green.
- `fdp36-real-chaos` must be green.
- `fdp35-production-readiness` must be green.
- Docker must be available; no Docker skip is acceptable for the required FDP-37 job.

## Required Proof Artifacts

- `alert-service/target/fdp37-chaos/fdp37-proof-summary.md`
- `alert-service/target/fdp37-chaos/fdp37-proof-summary.json`
- `alert-service/target/fdp37-chaos/fdp37-enablement-review-pack.md`
- `alert-service/target/fdp37-chaos/fdp37-enablement-review-pack.json`
- `alert-service/target/fdp37-chaos/evidence-summary.md`
- `alert-service/target/fdp37-chaos/fdp37-rollback-validation.md`
- `alert-service/target/fdp37-chaos/fdp37-rollback-validation.json`

The proof summary must contain:

- `fdp37-alert-service:${GITHUB_SHA}`
- current commit SHA
- immutable image id
- image digest or image id
- Dockerfile path `deployment/Dockerfile.backend`
- masked killed container id
- masked restarted container id
- `network_mode: testcontainers-shared-network`
- `host_networking_used: false`
- `live_in_flight_proof_executed: false`
- `READY_FOR_ENABLEMENT_REVIEW is not production enablement`

The evidence summary must contain:

- `PRODUCTION_IMAGE_CONTAINER_KILL`
- `PRODUCTION_IMAGE_RESTART_API_PROOF`
- `transaction_mode=REQUIRED`
- `network_mode=testcontainers-shared-network`
- `host_networking_used=false`
- no unrelated placeholder image evidence

The enablement review pack must contain job status, prerequisite status, image provenance, required transaction execution, rollback validation, proof artifact presence, `production_enablement: false`, `release_config_pr_required: true`, `human_approval_required: true`, and `operator_drill_required_before_enablement: true`.

## Required Tests

The FDP-37 CI job must fail if any required class XML is missing or skipped:

- `RegulatedMutationProductionImageChaosIT`
- `RegulatedMutationProductionImageEvidenceIntegrityIT`
- `RegulatedMutationProductionImageConfigParityIT`
- `RegulatedMutationProductionImageRollbackIT`
- `RegulatedMutationProductionImageRequiredTransactionChaosIT`

`RegulatedMutationProductionImageLiveInFlightKillIT` is optional fixture-gated future scope. A skipped live in-flight test is not counted as proof and is not part of the required FDP-37 merge gate.

## Non-Claims

FDP-37 does not provide production config certification, Docker Compose topology proof, production secrets validation, production Kafka delivery certification, external finality, distributed ACID, Kafka exactly-once delivery, legal notarization, bank certification, or production enablement.

`READY_FOR_ENABLEMENT_REVIEW` is not production enablement. Production or bank enablement requires a separate release/config PR and human approval.
