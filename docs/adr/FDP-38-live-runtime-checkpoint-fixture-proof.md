# FDP-38 Live Runtime Checkpoint Fixture Proof ADR

FDP-38 is a proof/testing branch. It proves selected live runtime checkpoint kill scenarios with a dedicated alert-service test-fixture image.

FDP-38 does not change production runtime semantics, public statuses, mutation types, transaction boundaries, Kafka/outbox semantics, or FDP-29 production enablement.

## Scope

FDP-38 uses `fdp38-alert-service-test-fixture:${GITHUB_SHA}`. The fixture image is built from the same alert-service application code plus test classes that provide the explicit `fdp38-live-runtime-checkpoint` profile.

The fixture image is not a production image, not a release image, not production certification, and not bank certification.

## Proof Taxonomy

- `DURABLE_STATE_SEEDED`: state was seeded durably before kill.
- `RUNTIME_REACHED_TEST_FIXTURE`: state was reached by a live HTTP request in the dedicated FDP-38 test-fixture image.
- `RUNTIME_REACHED_PRODUCTION_IMAGE`: final release-like image reached the state live. FDP-38 does not claim this.
- `FUTURE_SCOPE`: future evidence only.
- `LIVE_IN_FLIGHT_REQUEST_KILL`: a live request reached the barrier, the fixture container was killed, and post-restart evidence was collected.

FDP-38 required proof is `RUNTIME_REACHED_TEST_FIXTURE` plus `LIVE_IN_FLIGHT_REQUEST_KILL`.

## Required Live Checkpoints

- `BEFORE_LEGACY_BUSINESS_MUTATION`
- `AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION`
- `BEFORE_FDP29_LOCAL_FINALIZE`
- `BEFORE_SUCCESS_AUDIT_RETRY`

Every required checkpoint must be non-skipped, barrier-reached, container-killed, restarted, and artifact-backed in CI.

## Non-Claims

FDP-38 does not claim `RUNTIME_REACHED_PRODUCTION_IMAGE`, full instruction-boundary coverage, production enablement, production certification, bank certification, external finality, distributed ACID, Kafka exactly-once delivery, legal notarization, no WORM storage, or that the release image contains checkpoint barriers.
