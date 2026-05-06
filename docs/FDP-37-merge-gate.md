# FDP-37 Merge Gate

FDP-37 is mergeable only as production-image chaos proof and release-gate evidence. It is not production enablement.

## Required CI

- `fdp37-production-image-chaos` must be green.
- `fdp36-real-chaos` must be green.
- `regulated-mutation-regression` must be green.
- `fdp35-production-readiness` must be green.
- Docker must be available; no Docker skip is acceptable for the required FDP-37 job.
- `alert-service/target/fdp37-chaos/fdp37-proof-summary.md` must exist.
- `alert-service/target/fdp37-chaos/fdp37-proof-summary.json` must exist.
- `PRODUCTION_IMAGE_CONTAINER_KILL` must appear in the FDP-37 evidence artifact.

## Required Proof

- killed target is the production-like `alert-service` Docker image/container
- killed image is not dummy, alpine, busybox, or sleep container
- restarted container id differs from killed container id
- restarted image serves recovery/inspection API verification
- regulated mutation outbox/audit counts remain bounded
- no false committed success is reported
- no false external evidence confirmation is reported

## Non-Claims

FDP-37 does not provide:

- production enablement
- bank certification
- external finality
- distributed ACID
- distributed lock
- Kafka exactly-once
- automatic FDP-29 enablement

`READY_FOR_ENABLEMENT_REVIEW` is not production enablement. Production or bank enablement requires a separate release/config PR.
