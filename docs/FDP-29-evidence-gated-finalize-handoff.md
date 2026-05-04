# FDP-29 Local Evidence-Precondition-Gated Finalize Handoff

FDP-29 adds the first local evidence-precondition-gated finalize runtime path behind disabled-by-default feature flags. The implemented scope is limited to submit analyst decision.

The design addresses the remaining gap that FDP-28 intentionally documents instead of hiding: a regulated business mutation can become visible before all required success evidence is fully ready.

## Target Guarantee

When `app.regulated-mutations.evidence-gated-finalize.enabled=true`, `app.regulated-mutations.evidence-gated-finalize.submit-decision.enabled=true`, and `app.regulated-mutations.transaction-mode=REQUIRED`, submit-decision uses `EVIDENCE_GATED_FINALIZE_V1`. In that model, no externally visible submit-decision mutation is finalized unless required locally verifiable evidence preconditions are satisfied. New FDP-29 submit-decision commands persist `FINALIZED_EVIDENCE_PENDING_EXTERNAL` as the durable local-visible state; `FINALIZED_VISIBLE` is compatibility/repair state only.

## Current Implementation

The current v1 implementation proves the local path only:

- durable command and canonical intent exist
- durable `ATTEMPTED` audit exists before visible mutation
- transaction mode and Mongo transaction capability are required
- transactional outbox repository, outbox recovery, and submit-decision recovery strategy are required
- actor/resource/action intent matches the backend-resolved command
- submit-decision business validation passes before visible mutation
- local finalize transaction writes the alert decision, authoritative outbox record, response snapshot, local success audit evidence through `RegulatedMutationLocalAuditPhaseWriter`, and finalize marker together

External anchor readiness, Trust Authority signing readiness, independent witness readiness, Kafka broker delivery, WORM semantics, legal finality, and external evidence confirmation remain outside this local transaction.

## Design Contract

- ADR: `docs/adr/FDP-29-evidence-gated-finalize.md`
- Evidence preconditions: `docs/architecture/FDP-29-evidence-preconditions.md`
- State machine: `docs/architecture/FDP-29-state-machine.md`
- Compatibility matrix: `docs/architecture/FDP-29-compatibility-matrix.md`
- API response contract: `docs/api/FDP-29-api-response-contract.md`
- Finalize recovery runbook: `docs/runbooks/FDP-29-finalize-recovery-required.md`
- Failure windows: `docs/architecture/FDP-29-failure-windows.md`
- Idempotency replay: `docs/architecture/FDP-29-idempotency-replay.md`
- Migration and rollout: `docs/architecture/FDP-29-migration-rollout.md`
- Implementation merge checklist: `docs/FDP-29-implementation-merge-gate.md`
- Future test plan: `docs/testing/FDP-29-test-plan.md`
- Design checklist: `docs/FDP-29-design-checklist.md`

## Runtime Boundary

FDP-29 preserves FDP-25/FDP-26/FDP-27/FDP-28 compatibility when the feature flags are disabled. It does not migrate fraud-case update, trust incident writes, outbox confirmation resolution, Kafka contracts, scoring, ML model behavior, or UI workflow.

Startup fails closed if the submit-decision evidence-gated path is enabled without `transaction-mode=REQUIRED`, Mongo transaction capability, transactional outbox repository, outbox recovery, and submit-decision recovery strategy. Runtime checks remain as defense-in-depth.

The runtime model stores `mutation_model_version` on command records. Missing or null values are treated as `LEGACY_REGULATED_MUTATION`.

## Non-Claims

FDP-29 does not claim distributed ACID, exactly-once Kafka, external witness writes inside a local transaction, legal notarization, WORM storage, KMS/HSM-backed signing, or process-kill chaos coverage. External evidence confirmation remains asynchronous.
