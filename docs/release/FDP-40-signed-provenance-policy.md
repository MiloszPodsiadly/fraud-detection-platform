# FDP-40 Signed Provenance Readiness Policy

FDP-40 requires the fields needed for cosign, Sigstore, or SLSA-style signed provenance readiness. It does not claim that production signing infrastructure has already certified the system.

## Required Fields

- image_digest
- signature_subject
- certificate_identity
- certificate_issuer
- builder_identity
- source_repository
- commit_sha
- workflow_name
- workflow_run_id
- dockerfile_path
- build_type
- build_trigger
- provenance_predicate_type
- slsa_version_or_equivalent
- artifact_lineage_ref
- fdp39_provenance_ref

## NO-GO Rules

- Missing signature subject is NO-GO.
- Missing builder identity is NO-GO.
- Missing source repository is NO-GO.
- Missing commit SHA is NO-GO.
- Missing image digest is NO-GO.
- Digest mismatch with release manifest is NO-GO.
- Fixture image attestation cannot satisfy release image attestation.
- Mutable tag only attestation is NO-GO.

Signed artifact proof is release provenance evidence only. It does not claim external finality, distributed ACID, business correctness proof, production certification, or bank certification.
