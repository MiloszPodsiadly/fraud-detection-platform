# FDP-40 Branch README

FDP-40 implements platform release-controls readiness for regulated mutation enablement review.

It validates digest-bound release manifests, signed provenance readiness, attestation verification, registry promotion policy, required checks, environment protection, enablement PR evidence, unsupported claim controls, and runtime immutability.

## What FDP-40 Is

- Policy and evidence validation.
- Release readiness gate material.
- A bank-reviewable checklist for future controlled enablement.

## What FDP-40 Is Not

- Not production enablement.
- Not bank certification.
- Not external finality.
- Not distributed ACID.
- Not exactly-once Kafka.
- Not legal notarization.
- Not proof that signing proves business correctness.

## Merge Requirement

`fdp40-release-controls` must pass and produce `target/fdp40-release/fdp40-proof-pack.json` and `target/fdp40-release/fdp40-proof-pack.md`.

READY_FOR_ENABLEMENT_REVIEW is not PRODUCTION_ENABLED until a separate config PR is approved and merged.
