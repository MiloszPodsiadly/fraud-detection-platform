# FDP-38 Live Runtime Checkpoint Fixture Proof ADR

FDP-38 is a proof/testing branch. It proves selected live runtime checkpoint kill scenarios with a dedicated alert-service test-fixture image.

FDP-38 does not change production runtime semantics, public statuses, mutation types, transaction boundaries, Kafka/outbox semantics, or FDP-29 production enablement.

## Scope

FDP-38 uses `fdp38-alert-service-test-fixture:${GITHUB_SHA}`. The fixture image is built from the same alert-service application code plus test classes that provide the explicit `fdp38-live-runtime-checkpoint` profile.

The fixture image is not a production image, not a release image, not production deployable, not release-candidate eligible, not production certification, and not bank certification.

## Proof Taxonomy

- `DURABLE_STATE_SEEDED`: state was seeded durably before kill.
- `RUNTIME_REACHED_TEST_FIXTURE`: state was reached by a live HTTP request in the dedicated FDP-38 test-fixture image.
- `RUNTIME_REACHED_PRODUCTION_IMAGE`: final release-like image reached the state live. FDP-38 does not claim this.
- `FUTURE_SCOPE`: future evidence only.
- `LIVE_IN_FLIGHT_REQUEST_KILL`: a live request reached the barrier, the fixture container was killed, and post-restart evidence was collected.

FDP-38 required proof is `RUNTIME_REACHED_TEST_FIXTURE` plus `LIVE_IN_FLIGHT_REQUEST_KILL`.

## Precondition Setup Taxonomy

- `LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST`: the request starts through the fixture API and reaches the checkpoint live.
- `SEEDED_DURABLE_PRECONDITION_THEN_RUNTIME_REACHED_CHECKPOINT`: durable state is seeded first, then the restarted fixture reaches the named checkpoint live.

`BEFORE_LEGACY_BUSINESS_MUTATION`, `AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION`, and `BEFORE_FDP29_LOCAL_FINALIZE` use `LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST`.

`BEFORE_SUCCESS_AUDIT_RETRY` uses `SEEDED_DURABLE_PRECONDITION_THEN_RUNTIME_REACHED_CHECKPOINT`; it proves no duplicate business mutation, outbox, or SUCCESS audit after the retry checkpoint is reached live.

## Required Live Checkpoints

- `BEFORE_LEGACY_BUSINESS_MUTATION`
- `AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION`
- `BEFORE_FDP29_LOCAL_FINALIZE`
- `BEFORE_SUCCESS_AUDIT_RETRY`

Every required checkpoint must be non-skipped, barrier-reached, container-killed, restarted, and artifact-backed in CI.

## Fixture Isolation Fields

FDP-38 artifacts must record:

- `fixture_image: true`
- `release_image: false`
- `contains_test_classes: true`
- `contains_test_profiles: true`
- `release_candidate_allowed: false`
- `production_deployable: false`

## Non-Claims

FDP-38 does not claim `RUNTIME_REACHED_PRODUCTION_IMAGE`, full instruction-boundary coverage, production enablement, production certification, bank certification, external finality, distributed ACID, Kafka exactly-once delivery, legal notarization, WORM guarantee, or that the release image contains checkpoint barriers.
