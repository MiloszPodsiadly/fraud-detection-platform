# FDP-40 Branch README

FDP-40 implements platform release-controls readiness for regulated mutation enablement review.

It validates digest-bound release manifests, signed provenance readiness, attestation readiness, registry promotion policy readiness,
required checks mapping, environment protection readiness, enablement PR evidence, unsupported claim controls, and runtime immutability.

## What FDP-40 Is

- Policy and evidence validation.
- Release readiness gate material.
- A bank-reviewable checklist for future controlled enablement.
- Readiness-only proof that external platform controls are required before production enablement.

## What FDP-40 Is Not

- Not production enablement.
- Not bank certification.
- Not external finality.
- Not distributed ACID.
- Not exactly-once Kafka.
- Not legal notarization.
- Not proof that signing proves business correctness.
- Not real cosign signature verification unless explicit enforcement mode is enabled.
- Not Sigstore or Rekor verification.
- Not registry immutability enforcement.
- Not GitHub branch protection enforcement.
- Not GitHub environment protection enforcement.

## Merge Requirement

`fdp40-release-controls` must pass and produce `target/fdp40-release/fdp40-proof-pack.json` and `target/fdp40-release/fdp40-proof-pack.md`.

READY_FOR_ENABLEMENT_REVIEW is not PRODUCTION_ENABLED until a separate config PR is approved and merged.

FDP-40 proof artifacts must contain:

- `readiness_only: true`
- `production_enabled: false`
- `external_platform_controls_required: true`
- `signing_verification_performed: false`
- `registry_immutability_enforced_by_fdp40: false`
- `environment_protection_verified_by_fdp40: false`
- `branch_protection_verified_by_fdp40: false`
