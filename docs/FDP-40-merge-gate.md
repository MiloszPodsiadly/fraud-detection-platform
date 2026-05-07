# FDP-40 Merge Gate

FDP-40 is merge-safe only as platform release-controls readiness. It must not be used as production enablement, bank certification, external finality, or distributed ACID proof.

## Required Checks

- backend
- docker
- regulated-mutation-regression
- fdp35-production-readiness
- fdp36-real-chaos
- fdp37-production-image-chaos
- fdp38-live-runtime-checkpoint-chaos
- fdp39-release-governance
- fdp40-release-controls

Each required check must be blocking and mapped in `docs/release/FDP-40-required-checks-matrix.json`.

## Required Artifacts

- `fdp40-release-evidence-verification.json`
- `fdp40-registry-promotion-policy.json`
- `fdp40-runtime-immutability.json`
- `fdp40-proof-pack.json`
- `fdp40-proof-pack.md`

## GO Criteria

- Release manifest is digest-bound.
- Release manifest digest matches FDP-39 release image digest.
- Attestation readiness has signature subject, builder identity, source repository, workflow run id, and image digest.
- Fixture image is not promotable.
- Mutable tag only is NO-GO.
- Production enablement remains false.
- Dual control and separate config PR are required.
- Runtime mutation semantics are unchanged.
- Unsupported claims are explicitly denied.

## NO-GO Criteria

- Missing release digest or image id.
- Digest mismatch between manifest, attestation, and FDP-39 provenance.
- Fixture image used as release or promotion image.
- Required check missing, non-blocking, or not required.
- Environment approval missing.
- Runtime mutation semantic file changed.
- Any positive claim of production certification, bank certification, external finality, distributed ACID, exactly-once Kafka, legal notarization, or business correctness from signing.
