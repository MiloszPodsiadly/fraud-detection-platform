# FDP-39 Final Regulated Mutation Proof Matrix

Fixture proof is not production proof. `READY_FOR_ENABLEMENT_REVIEW` is not `PRODUCTION_ENABLED`.

| Branch | Proof level | Image/process used | State reach method | CI job | Artifact name | Claims allowed | Claims forbidden | ACID boundary impact | Production enablement impact | Residual gaps |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| FDP-35 modeled restart/recovery proof | modeled restart and recovery readiness | test/runtime harness, not killed production process | modeled restart/recovery | `fdp35-production-readiness` | production readiness reports | readiness evidence for modeled recovery behavior | real process-kill proof, production enablement | local transaction semantics unchanged | none | no real process kill |
| FDP-36 real alert-service JVM/process kill proof | real alert-service kill/restart | actual alert-service JVM/process | runtime reached process kill windows | `fdp36-real-chaos` | `fdp36-proof-summary.md` | selected real process kill recovery evidence | release image production proof, bank certification | local transaction semantics unchanged | none | no release image certification |
| FDP-37 production-like release image durable-state chaos proof | production-like image container kill | `deployment/Dockerfile.backend` image | durable seeded state | `fdp37-production-image-chaos` | `fdp37-proof-summary.json` | durable-state production-like image restart evidence | live instruction-boundary checkpoint proof | local transaction semantics unchanged | review-ready only | no all-instruction-boundary live chaos |
| FDP-38 test-fixture live runtime checkpoint proof | `LIVE_IN_FLIGHT_REQUEST_KILL` | dedicated FDP-38 test-fixture image | `RUNTIME_REACHED_TEST_FIXTURE` | `fdp38-live-runtime-checkpoint-chaos` | `fdp38-proof-summary.json` | selected live runtime checkpoint proof in fixture | `RUNTIME_REACHED_PRODUCTION_IMAGE`, release image proof | local transaction semantics unchanged | none | fixture image is not release image |

## Explicit Residual Gaps

- no external finality
- no distributed ACID
- no Kafka exactly-once delivery
- no legal/WORM notarization
- no automatic FDP-29 production enablement
- no full production config certification
- no proof that fixture image is release image
- no all-instruction-boundary live chaos coverage
- no registry immutability enforcement
- no image signing, Sigstore/cosign, or SLSA attestation enforcement
- no runtime admission controller policy
- no production registry promotion-control enforcement
- no production environment protection-rule enforcement outside GitHub

## Final Allowed Claim

FDP-39 proves release artifact separation and enablement governance hardening for regulated mutation proof artifacts. It does not enable production mode and does not replace enterprise registry, signing, admission-control, or production promotion controls.
