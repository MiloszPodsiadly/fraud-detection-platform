# FDP-40 Platform Release Controls and Signed Provenance Readiness

## Status

Accepted for readiness validation.

## Context

FDP-39 proved release artifact separation and governance evidence for regulated mutation release readiness. FDP-40 moves one layer higher and defines platform release controls for digest-bound manifests, signed provenance readiness, registry promotion policy, environment protection, required checks, and enablement approval templates.

## Decision

FDP-40 is a release and platform governance readiness branch. It validates the evidence required before any future regulated mutation production enablement request can be reviewed.

FDP-40 does not change runtime mutation behavior, public statuses, transaction boundaries, Kafka or outbox semantics, or FDP-29 production enablement.

## Required Evidence

- Digest-bound release manifest.
- Signed provenance and attestation readiness fields.
- Verification that manifest, attestation, and FDP-39 provenance agree on the release digest.
- Registry immutability and promotion policy.
- Required checks and branch protection mapping.
- Environment protection and dual-control approval gates.
- Enablement PR template for future config-only enablement.
- Unsupported claims matrix.
- Runtime immutability proof.
- Final proof pack.

## Non-Claims

Signed provenance does not claim external finality.

Release approval is not distributed ACID.

Environment approval does not claim operational correctness.

READY_FOR_ENABLEMENT_REVIEW is not PRODUCTION_ENABLED.

FDP-40 is not production certification and is not bank certification.
