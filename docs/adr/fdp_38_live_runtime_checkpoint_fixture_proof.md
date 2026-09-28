# FDP-38 Live Runtime Checkpoint Fixture Proof

## Decision

Use a dedicated alert-service test-fixture image to prove current `EVIDENCE_GATED_FINALIZE_V1` runtime barriers. The fixture image is not a production image and FDP-38 is not production enablement.

The fixture registers exactly these current-runtime checkpoints:

- `BEFORE_EVIDENCE_PREPARATION`
- `AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE`
- `BEFORE_EVIDENCE_GATED_FINALIZE`

Each checkpoint uses `LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST`, reaches the barrier in the test fixture, kills the in-flight container, restarts it, and evaluates false-success and duplicate-prevention evidence. No retired-model persisted state is seeded.

## Evidence Boundary

The proof level is `LIVE_IN_FLIGHT_REQUEST_KILL` and the state reach method is `RUNTIME_REACHED_TEST_FIXTURE`. FDP-38 does not claim `RUNTIME_REACHED_PRODUCTION_IMAGE`.

Artifacts must identify the fixture image and commit, report `release_image: false`, `contains_test_classes: true`, `contains_test_profiles: true`, `release_candidate_allowed: false`, `production_deployable: false`, `production_enablement: false`, `false_success_evaluation`, and `failed_false_success_reasons: []`.

Every `Fdp38LiveRuntimeCheckpoint` enum value must be represented by the proof matrix, CI selector, artifact verifier, and a non-skipped test. The release image does not contain checkpoint barrier support.
