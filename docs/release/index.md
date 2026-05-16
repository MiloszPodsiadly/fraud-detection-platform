# Release Documentation

Status: current release documentation index.

## Start Here

| Document | Purpose |
| --- | --- |
| [Release governance](release_governance.md) | Current human-readable release-control model and non-claims. |
| [FDP-39 release artifact separation](fdp_39_branch_readme.md) | Branch evidence for separating release images, fixture images, and proof artifacts. |
| [FDP-40 release controls readiness](fdp_40_branch_readme.md) | Branch evidence for release-control readiness and external platform gaps. |
| [FDP-40 proof artifact schema](fdp_40_proof_artifact_schema.md) | Expected generated proof-pack fields. |
| [FDP-40 required checks matrix](fdp_40_required_checks_matrix.md) | Required CI checks that external branch protection must enforce before production enablement. |
| [FDP-40 external platform controls](fdp_40_external_platform_controls_matrix.md) | External controls required before production enablement. |

## Machine-Validated Templates

These files are intentionally separate because scripts and tests validate them directly:

- `fdp_40_release_manifest_template.yaml`
- `fdp_40_attestation_readiness_template.json`
- `fdp_40_fdp39_provenance_reference.json`
- `fdp_40_required_checks_matrix.json`
- `fdp_40_registry_promotion_policy.json`
- `fdp_40_external_platform_controls_matrix.json`
- `fdp_40_environment_protection_gates.json`
- `fdp_40_branch_protection_readiness.json`
- `fdp_40_deployment_environment_readiness.json`
- `fdp_40_registry_immutability_readiness.json`
- `fdp_40_single_release_owner_governance.json`
- `fdp_40_sbom_readiness_template.json`
- `fdp_39_fixture_dockerfile_allowlist.json`

## Evidence Interpretation

FDP-39 and FDP-40 documents are readiness and governance evidence. They are not production enablement.

`READY_FOR_ENABLEMENT_REVIEW` is not `PRODUCTION_ENABLED`.

Release evidence does not claim external finality, distributed ACID, Kafka exactly-once delivery, WORM storage, legal
notarization, bank certification, production registry enforcement, branch protection enforcement, environment
protection enforcement, or business correctness proof.

## Folder Rules

- Keep generated-proof schemas and JSON/YAML templates stable unless tests and scripts are updated in the same change.
- Keep release image proof separate from fixture image proof.
- Keep mutable tag usage as NO-GO for production enablement.
- Keep branch evidence under `fdp_39_*` and `fdp_40_*` names because CI and tests use those prefixes as proof families.
- Add current narrative docs only when they make review easier; do not duplicate machine-validated JSON fields manually.
