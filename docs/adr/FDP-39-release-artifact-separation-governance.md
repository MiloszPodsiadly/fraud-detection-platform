# FDP-39 Release Artifact Separation Governance ADR

FDP-39 is a governance, proof, and release-control branch. It prevents confusion between previous regulated mutation proof artifacts and production enablement.

## Context

FDP-37 proves durable-state chaos against a production-like alert-service release image built from `deployment/Dockerfile.backend`.

FDP-38 proves selected live runtime checkpoint kills with a dedicated test-fixture image built from `deployment/Dockerfile.alert-service-fdp38-fixture`.

Those proofs are useful only if release governance keeps the artifacts separated and binds every claim to immutable provenance.

## Decision

FDP-39 adds release governance tests, CI guards, proof artifacts, and release documentation. It does not change runtime mutation semantics.

The release image must exclude FDP-38 fixture and test checkpoint code. The FDP-38 fixture image is not a release image, not release-candidate eligible, and not production deployable.

`READY_FOR_ENABLEMENT_REVIEW` is not `PRODUCTION_ENABLED`. Production or bank enablement requires a separate release/config PR, dual control, rollback ownership, operator drill evidence, security review, and an audit record.

## Non-Goals

FDP-39 does not add mutation types, public statuses, transaction boundaries, Kafka/outbox semantics, executor behavior, coordinator behavior, external finality, distributed ACID, Kafka exactly-once delivery, legal notarization, WORM guarantee, production certification, bank certification, or automatic FDP-29 production enablement.

## Required Evidence

- release image separation proof
- fixture Dockerfile usage proof
- immutable artifact provenance proof
- enablement governance pack
- dual-control and rollback governance proof
- final FDP-35 through FDP-38 proof matrix
- no-overclaim guard
- runtime immutability guard
