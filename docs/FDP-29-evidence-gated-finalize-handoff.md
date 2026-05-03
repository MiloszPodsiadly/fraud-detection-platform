# FDP-29 Evidence-Gated Finalize Handoff

FDP-29 adds the first evidence-gated finalize runtime path behind disabled-by-default feature flags. The implemented scope is limited to submit analyst decision.

The design addresses the remaining gap that FDP-28 intentionally documents instead of hiding: a regulated business mutation can become visible before all required success evidence is fully ready.

## Target Guarantee

When `app.regulated-mutations.evidence-gated-finalize.enabled=true`, `app.regulated-mutations.evidence-gated-finalize.submit-decision.enabled=true`, and `app.regulated-mutations.transaction-mode=REQUIRED`, submit-decision uses `EVIDENCE_GATED_FINALIZE_V1`. In that model, no externally visible submit-decision mutation becomes `FINALIZED_VISIBLE` unless required locally verifiable evidence preconditions are satisfied.

## Design Contract

- ADR: `docs/adr/FDP-29-evidence-gated-finalize.md`
- Evidence preconditions: `docs/architecture/FDP-29-evidence-preconditions.md`
- State machine: `docs/architecture/FDP-29-state-machine.md`
- Compatibility matrix: `docs/architecture/FDP-29-compatibility-matrix.md`
- API response contract: `docs/api/FDP-29-api-response-contract.md`
- Failure windows: `docs/architecture/FDP-29-failure-windows.md`
- Idempotency replay: `docs/architecture/FDP-29-idempotency-replay.md`
- Migration and rollout: `docs/architecture/FDP-29-migration-rollout.md`
- Future test plan: `docs/testing/FDP-29-test-plan.md`
- Design checklist: `docs/FDP-29-design-checklist.md`

## Runtime Boundary

FDP-29 preserves FDP-25/FDP-26/FDP-27/FDP-28 compatibility when the feature flags are disabled. It does not migrate fraud-case update, trust incident writes, outbox confirmation resolution, Kafka contracts, scoring, ML model behavior, or UI workflow.

The runtime model stores `mutation_model_version` on command records. Missing or null values are treated as `LEGACY_REGULATED_MUTATION`.

## Non-Claims

FDP-29 does not claim distributed ACID, exactly-once Kafka, external witness writes inside a local transaction, legal notarization, WORM storage, KMS/HSM-backed signing, or process-kill chaos coverage. External evidence confirmation remains asynchronous.
