# FDP-39 Merge Gate

Status: branch evidence.


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
- `alert-service/target/fdp39-governance/fdp39-ci-proof-summary.md`
- `alert-service/target/fdp39-governance/fdp39-runtime-immutability.json`
- `alert-service/target/fdp39-governance/fdp39-fixture-dockerfile-usage.json`
- `alert-service/target/fdp39-governance/fdp39-ops-inspection-governance.json`

## GO Criteria

- Release image uses `deployment/Dockerfile.backend`.
- Release image has immutable image id or digest evidence.
- Release image filesystem is exported in CI and scanned with `release_image_scan_performed=true`, `scanned_file_count>0`, `forbidden_token_count=0`, and `release_image_safe=true`.
- Fixture Dockerfile usage is enforced by `docs/release/fdp_39_fixture_dockerfile_allowlist.json`.
- Enablement evidence may reach `READY_FOR_ENABLEMENT_REVIEW`; it is not `PRODUCTION_ENABLED`.
- Enablement governance says `production_enabled=false` and `bank_enabled=false`.
- Dual control, rollback owner, operator drill, security review, and audit record are required.
- Ops recovery/inspection endpoints are admin-only or equivalent authority, masked, audited, rate-limit verified, and audit-failure policy verified.
- Runtime immutability guard is fail-closed in CI and writes `runtime_semantics_unchanged=true`.

## NO-GO Criteria

FDP-39 is NO-GO if it changes runtime mutation semantics, adds public statuses, changes transaction boundaries, changes Kafka/outbox semantics, enables FDP-29 production mode, allows fixture image promotion, accepts mutable tag-only proof, or claims production enablement, bank certification, external finality, distributed ACID, Kafka exactly-once delivery, legal notarization, WORM guarantee, or full production config certification.

FDP-39 is also NO-GO if branch protection, required CI checks, image signing or attestation, registry immutability, deployment environment protection, dual-control release approval, and release/config PR requirements are presented as already enforced by FDP-39 alone. FDP-39 is repository/CI governance hardening, not a complete enterprise release-control platform.
