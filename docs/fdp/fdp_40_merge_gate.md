# FDP-40 Merge Gate

Status: branch evidence.


FDP-40 is merge-safe only as platform release-controls readiness. It must not be used as production enablement, bank certification, external finality, or distributed ACID proof.

FDP-40 provides signing/provenance readiness validation, digest-bound release evidence validation, promotion policy readiness,
environment protection readiness, required checks mapping, enablement governance templates, and a no-overclaim guard.

FDP-40 does not provide real cosign signature verification, Sigstore/Rekor verification, registry immutability enforcement,
GitHub branch protection enforcement, GitHub environment protection enforcement, production certification, bank certification,
external finality, distributed ACID, exactly-once Kafka, or production enablement.

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

Each required check must be blocking and mapped in `docs/release/fdp_40_required_checks_matrix.json`.

## Required Artifacts

- `fdp40-release-evidence-verification.json`
- `fdp40-registry-promotion-policy.json`
- `fdp40-runtime-immutability.json`
- `fdp40-proof-pack.json`
- `fdp40-proof-pack.md`
- `fdp40-cosign-verification.json`
- `fdp40-external-platform-controls-matrix.json`

## GO Criteria

- Release manifest is digest-bound.
- Release manifest digest matches FDP-39 release image digest.
- Attestation readiness has signature subject, builder identity, source repository, workflow run id, and image digest.
- Fixture image is not promotable.
- Mutable tag only is NO-GO.
- Production enablement remains false.
- Single release owner model and separate config PR are required.
- Release owner must be named and accountable for digest, checks, rollback, operator drill, and config PR evidence.
- Runtime mutation semantics are unchanged.
- Unsupported claims are explicitly denied.
- Proof artifacts state `readiness_only: true`.
- Proof artifacts state `production_enabled: false`.
- Proof artifacts state external platform controls are required.
- Proof artifacts state signing, branch protection, environment protection, and registry immutability enforcement were not performed by FDP-40.

## NO-GO Criteria

- Missing release digest or image id.
- Digest mismatch between manifest, attestation, and FDP-39 provenance.
- Fixture image used as release or promotion image.
- Required check missing, non-blocking, or not required.
- Environment approval missing.
- Runtime mutation semantic file changed.
- Any forbidden positive claim of production certification, bank certification, external finality, distributed ACID, exactly-once Kafka, legal notarization, or business correctness from signing.
