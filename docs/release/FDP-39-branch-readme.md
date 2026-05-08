# FDP-39 Branch README

## Purpose

Release artifact separation and enablement governance hardening.

## What This Proves

- release image excludes fixture/test checkpoint code
- fixture image cannot be promoted by the guarded workflow
- proof artifacts are digest-bound or image-id-bound
- enablement requires explicit release/config PR and dual control
- ops inspection endpoints are admin-only or equivalent authority, masked, audited, and rate-limit-required before production

## What This Does Not Prove

- production enablement
- bank certification
- no external finality
- no distributed ACID
- no Kafka exactly-once delivery
- no legal notarization
- no WORM guarantee

## What FDP-39 Does Not Replace

FDP-39 provides repository/CI governance hardening and auditable release evidence. It is not a complete enterprise release-control platform.

FDP-39 does not implement registry immutability, image signing, Sigstore/cosign attestations, SLSA provenance, deployment environment protection rules, production registry promotion controls, runtime admission controller policy, or real production approval enforcement outside GitHub.

Production enablement still requires image signing or equivalent attestation, immutable registry tags or digests, protected deployment environments, branch protection, enforced required CI checks, dual control, and a separate release/config PR.

## CI Gates

- `regulated-mutation-regression`
- `fdp35-production-readiness`
- `fdp36-real-chaos`
- `fdp37-production-image-chaos`
- `fdp38-live-runtime-checkpoint-chaos`
- `fdp39-release-governance`

## Required Artifacts

- `fdp39-release-image-separation.json`
- `fdp39-fixture-dockerfile-usage.md`
- `fdp39-fixture-dockerfile-usage.json`
- `fdp39-artifact-provenance.json`
- `fdp39-enablement-governance-pack.json`
- `fdp39-rollback-governance.json`
- `fdp39-runtime-immutability.json`
- `fdp39-ops-inspection-governance.json`
- `fdp39-ci-proof-summary.md`

## Docker E2E / Governance Output

Paste final Docker E2E and governance output here during branch review.

## Merge Checklist

- release image separation validated
- release image filesystem scan says `release_image_scan_performed=true`, `forbidden_token_count=0`, and `scanned_file_count>0`
- fixture Dockerfile release usage blocked by structural allowlist
- immutable provenance present
- enablement governance pack generated
- rollback governance generated
- final proof matrix present
- no-overclaim guard green
- runtime immutability guard green and fail-closed in CI
- ops inspection governance artifact covers auth, masking, audit, rate limit, and audit failure policy

## Reviewer Checklist

- verify fixture proof is not production proof
- verify `READY_FOR_ENABLEMENT_REVIEW` is not `PRODUCTION_ENABLED`
- verify generated artifacts are placeholder-free
- verify no runtime semantics changed

## Rollback Checklist

- keep FDP-32 fencing active
- keep recovery inspection visible
- verify no false success appears
- verify recovery commands remain visible
- verify FDP-29 flags can be disabled

## Final Allowed Claim

FDP-39 proves release artifact separation and enablement governance hardening. It does not enable production mode and does not replace enterprise registry, signing, admission-control, or production promotion controls.
