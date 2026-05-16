# Release Governance

Status: current release governance source of truth.

## Purpose

This document summarizes the release-control posture implemented in the repository. Detailed FDP-39 and FDP-40 proof
files remain branch evidence and machine-validated templates.

## Current Model

The repository supports release review through:

- release image and fixture image separation
- digest-bound release manifest templates
- signed provenance readiness fields
- attestation readiness fields
- required CI checks matrix
- registry promotion readiness policy
- external platform control matrix
- environment and branch protection readiness templates
- single release owner governance
- unsupported-claim guardrails

These controls make the release package reviewable. They do not enable production by themselves.

## Release Flow

1. Build and test the release candidate with required CI jobs.
2. Produce FDP-39 release-governance evidence that separates release images from fixture proof images.
3. Produce FDP-40 release-control readiness evidence from the manifest, attestation, FDP-39 provenance reference, and required checks matrix.
4. Review external platform controls that are required but not enforced by this repository.
5. Open a separate release/config PR before any future production enablement request.
6. Require the named release owner to confirm digest, required checks, rollback plan, operator drill evidence, and config PR.

## Required Before Production Enablement

| Control | Repository readiness | External enforcement required |
| --- | --- | --- |
| Release image digest binding | Yes, through `fdp_40_release_manifest_template.yaml`. | Yes. Deployment must reference immutable digest. |
| CI required checks matrix | Yes, through `fdp_40_required_checks_matrix.json`. | Yes. Branch protection must enforce it. |
| Signed provenance readiness | Yes, through manifest and attestation templates. | Yes. Real signing/Sigstore/SLSA verification must run outside readiness mode. |
| Registry immutability | Readiness only. | Yes. Registry provider must enforce non-overwrite policy. |
| Registry promotion by digest | Readiness only. | Yes. Promotion workflow must reject mutable-tag-only promotion. |
| Environment protection | Readiness only. | Yes. Deployment platform must enforce required reviewers and environment gates. |
| SBOM retention | Readiness only. | Yes. Release SBOM must be generated, retained, and reviewed. |
| Approval audit trail | Template/governance only. | Yes. Release platform must retain approval evidence. |

## Allowed Claims

- Release artifact separation has repository evidence.
- Fixture proof is not production proof.
- Release controls are ready for enablement review when all FDP-40 checks pass.
- External platform controls are explicitly required before production enablement.
- The release owner model requires a named accountable owner for future enablement review.

## Unsupported Claims

- `READY_FOR_ENABLEMENT_REVIEW` does not mean `PRODUCTION_ENABLED`.
- A signed image does not mean external finality.
- A release image digest does not mean Kafka exactly-once delivery.
- Release-control readiness does not mean business correctness proof.
- FDP-40 readiness does not mean real cosign, Sigstore, Rekor, SLSA, registry, branch protection, or environment enforcement has occurred.
- This repository does not provide WORM storage, legal notarization, bank certification, distributed ACID, or production registry policy enforcement.

## Reviewer Checklist

- Confirm the release image digest is immutable and not a mutable tag only.
- Confirm fixture images are not promoted as release candidates.
- Confirm all required CI checks are green and externally required.
- Confirm generated proof artifacts contain `production_enabled: false`.
- Confirm external platform gaps are either closed with evidence or marked NO-GO.
- Confirm a separate release/config PR exists for any enablement request.
