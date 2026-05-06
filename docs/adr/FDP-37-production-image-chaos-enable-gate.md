# FDP-37 Production Image Chaos Enablement Gate ADR

FDP-37 is a proof, operations, and release-gate branch. It builds the `alert-service` image from the release Dockerfile, tags it with the current commit SHA, kills the real image/container, restarts that image against durable Mongo state, and verifies regulated mutation API, recovery, outbox, audit, and inspection invariants after restart.

FDP-37 does not change runtime semantics, public statuses, transaction boundaries, Kafka/outbox semantics, or production flags. It does not enable FDP-29 production mode and does not add production chaos hooks.

FDP-37 runs the production-like image under explicit CI/test configuration against Testcontainers dependencies. This is production-image chaos proof, not production environment configuration certification.

## Proof Levels

- `PRODUCTION_IMAGE_CONTAINER_KILL`: the killed target is the real production-like `alert-service` Docker image/container built from this commit.
- `PRODUCTION_IMAGE_RESTART_API_PROOF`: restarted API inspection/recovery verifies persisted regulated mutation state.
- `DURABLE_STATE_SEEDED_CONTAINER_PROOF`: durable crash-window state is seeded before killing the production-like image.
- `API_PERSISTED_STATE_PROOF`: verification is performed through persisted Mongo/API evidence, not process memory.

`PRODUCTION_IMAGE_CONTAINER_KILL` may only be claimed when the killed target image/container name contains `alert-service` and is not an unrelated placeholder image. The proof artifact must include image name, tag, digest or image id, commit SHA, job name, run id, masked killed and restarted container ids, network mode, OS, runner, scenario count, and final result.

## Required Scope

FDP-37 covers selected durable regulated mutation crash windows:

- claimed before attempted audit
- attempted audit before business mutation
- legacy business committing requiring recovery
- legacy success audit pending replay/recovery
- FDP-29 finalizing recovery-required without external confirmation
- FDP-29 finalized evidence pending external
- at least one `transaction-mode=REQUIRED` production-image chaos scenario
- rollback validation with no new success claims

Live in-flight production-image chaos is optional/future scope unless a separate fixture job explicitly enables a test-only checkpoint image and makes skipped execution fail CI. A skipped live in-flight test is not counted as proof.

## Network Boundary

FDP-37 production-image chaos uses Linux CI host networking for Testcontainers dependency access. The proof artifact records `network_mode: host`, `os_name`, and `ci_runner`.

This is not Docker Compose topology certification and not production networking certification. A future branch may replace host networking with a shared Testcontainers network and aliases.

## Non-Claims

FDP-37 does not certify production environment config, production networking, production secrets, production Kafka delivery, external finality, distributed ACID, Kafka exactly-once delivery, legal non-repudiation, or bank certification. FDP-37 is not production enablement.

## Merge Gate

FDP-37 GO for merge requires:

- `fdp37-production-image-chaos` green on `ubuntu-latest`
- required FDP-37 production-image chaos test classes executed with zero skips
- `regulated-mutation-regression` green
- `fdp36-real-chaos` green
- `fdp35-production-readiness` green
- `target/fdp37-chaos/fdp37-proof-summary.md` uploaded
- `target/fdp37-chaos/fdp37-proof-summary.json` uploaded
- `target/fdp37-chaos/evidence-summary.md` uploaded
- `target/fdp37-chaos/fdp37-rollback-validation.md` uploaded
- proof summary tied to `fdp37-alert-service:${GITHUB_SHA}` and image digest/id
- evidence contains `PRODUCTION_IMAGE_CONTAINER_KILL`, `PRODUCTION_IMAGE_RESTART_API_PROOF`, and a `transaction_mode=REQUIRED` row

`READY_FOR_ENABLEMENT_REVIEW` is not production enablement. Any production or bank enablement requires a separate release/config PR and human approval.
