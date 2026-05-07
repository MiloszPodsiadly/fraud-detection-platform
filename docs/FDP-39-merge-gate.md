# FDP-39 Merge Gate

FDP-39 is mergeable only as release artifact separation and enablement governance hardening. It is not production enablement.

## Required CI

- `regulated-mutation-regression`
- `fdp35-production-readiness`
- `fdp36-real-chaos`
- `fdp37-production-image-chaos`
- `fdp38-live-runtime-checkpoint-chaos`
- `fdp39-release-governance`

## Required Artifacts

- `alert-service/target/fdp39-governance/fdp39-release-image-separation.json`
- `alert-service/target/fdp39-governance/fdp39-fixture-dockerfile-usage.md`
- `alert-service/target/fdp39-governance/fdp39-artifact-provenance.json`
- `alert-service/target/fdp39-governance/fdp39-artifact-provenance.md`
- `alert-service/target/fdp39-governance/fdp39-enablement-governance-pack.json`
- `alert-service/target/fdp39-governance/fdp39-enablement-governance-pack.md`
- `alert-service/target/fdp39-governance/fdp39-rollback-governance.json`

## GO Criteria

- Release image uses `deployment/Dockerfile.backend`.
- Release image has immutable image id or digest evidence.
- Release image does not contain FDP-38 fixture/test checkpoint code.
- Fixture Dockerfile appears only in FDP-38 CI, FDP-38 docs/tests, and FDP-39 guard docs/tests.
- Enablement governance says `production_enabled=false` and `bank_enabled=false`.
- Dual control, rollback owner, operator drill, security review, and audit record are required.
- Ops recovery/inspection endpoints are admin-only or equivalent authority, masked, audited, and rate-limit-required before production.
- Runtime immutability guard is green.

## NO-GO Criteria

FDP-39 is NO-GO if it changes runtime mutation semantics, adds public statuses, changes transaction boundaries, changes Kafka/outbox semantics, enables FDP-29 production mode, allows fixture image promotion, accepts mutable tag-only proof, or claims production enablement, bank certification, external finality, distributed ACID, Kafka exactly-once delivery, legal notarization, WORM guarantee, or full production config certification.
