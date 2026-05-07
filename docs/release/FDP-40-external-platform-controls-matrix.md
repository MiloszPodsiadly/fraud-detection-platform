# FDP-40 External Platform Controls Matrix

FDP-40 validates release-control readiness inside this repository. It does not enforce external platform controls.

Production enablement is NO-GO unless every external control has independent platform evidence:

- cosign/Sigstore signing verification
- Rekor or transparency log verification if Sigstore is used
- registry immutability and tag overwrite protection
- promotion by immutable image digest
- GitHub branch protection required checks
- GitHub environment required reviewers
- deployment environment protection
- artifact retention policy
- release approval audit trail
- SBOM generation and retention
- provenance attestation retention
- rollback approval trail

Each control has `required_before_production_enablement: true`, `enforced_by_fdp40: false`, an owner, required evidence, and `failure_policy: NO_GO_FOR_PRODUCTION_ENABLEMENT`.

FDP-40 readiness is not full platform enforcement.
