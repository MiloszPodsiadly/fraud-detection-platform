# FDP-37 Production Image Chaos Enablement Gate ADR

FDP-37 is a proof, operations, and release-gate branch. It builds and kills a real production-like `alert-service` Docker image/container, restarts the same image configuration against durable Mongo state, and verifies regulated mutation API, recovery, outbox, audit, and inspection invariants after restart.

FDP-37 does not change runtime semantics, public statuses, transaction boundaries, Kafka/outbox semantics, or production flags. It does not enable FDP-29 production mode and does not add production chaos hooks.

## Proof Levels

- `REAL_ALERT_SERVICE_JVM_KILL`: FDP-36 JVM/process kill of the real `AlertServiceApplication`.
- `LIVE_IN_FLIGHT_REQUEST_KILL`: a live HTTP request is in flight at a controlled checkpoint when the target is killed.
- `PRODUCTION_IMAGE_CONTAINER_KILL`: FDP-37 kill of the real production-like `alert-service` Docker image/container.
- `PRODUCTION_IMAGE_RESTART_API_PROOF`: restarted production-like image serves recovery/inspection/API verification.
- `DURABLE_STATE_SEEDED_CONTAINER_PROOF`: durable crash-window state is seeded before killing the production-like image.
- `API_PERSISTED_STATE_PROOF`: post-restart evidence is verified through API plus durable Mongo state.

`PRODUCTION_IMAGE_CONTAINER_KILL` may only be claimed when the killed target image/container name contains `alert-service` and is not a dummy image such as `alpine`, `busybox`, or a sleep container.

## Scope

FDP-37 covers selected regulated mutation crash windows:

- legacy after claim before attempted audit
- legacy after attempted audit before business mutation
- legacy `BUSINESS_COMMITTING`
- legacy `SUCCESS_AUDIT_PENDING`
- FDP-29 `FINALIZING`
- FDP-29 `FINALIZED_EVIDENCE_PENDING_EXTERNAL`
- post-restart duplicate outbox/audit evidence integrity
- production-image config parity
- rollback validation

## Non-Goals

- no production enablement
- no bank certification
- no external finality
- no distributed ACID
- no distributed lock
- no Kafka exactly-once claim
- no new public statuses
- no production runtime chaos hook

## Merge Gate

FDP-37 GO for merge requires:

- `fdp37-production-image-chaos` green
- `regulated-mutation-regression` green
- `fdp36-real-chaos` green
- `fdp35-production-readiness` green
- `target/fdp37-chaos/fdp37-proof-summary.md` uploaded
- `target/fdp37-chaos/fdp37-proof-summary.json` uploaded

`READY_FOR_ENABLEMENT_REVIEW` is not production enablement. Any production or bank enablement requires a separate release/config PR.
