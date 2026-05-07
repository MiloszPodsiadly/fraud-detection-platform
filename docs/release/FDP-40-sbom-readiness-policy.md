# FDP-40 SBOM Readiness Policy

FDP-40 requires SBOM readiness before production enablement, but it does not generate a production SBOM.

Required external evidence:

- SPDX or CycloneDX SBOM for the release image digest
- retained SBOM artifact
- vulnerability review
- security owner approval
- build workflow and commit SHA linkage

SBOM readiness is not runtime correctness proof, external finality, production certification, or bank certification.
