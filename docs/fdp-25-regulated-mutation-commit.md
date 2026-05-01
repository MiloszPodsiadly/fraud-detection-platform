# FDP-25 Regulated Mutation Commit Model

FDP-25 is the regulated mutation commit protocol for the first migrated write path: `POST /api/v1/alerts/{alertId}/decision`.

FDP-24 remains intentionally narrower. FDP-24 provides external audit anchoring, fail-closed `ATTEMPTED` audit persistence, durable post-commit degradation detection, explicit `COMMITTED_EVIDENCE_PENDING` / `COMMITTED_EVIDENCE_INCOMPLETE` states, and recovery/reconciliation workflows. FDP-24 detects and exposes post-commit audit degradation. It does not eliminate it transactionally.

## Goal

Stop domain services from hand-assembling audit, business mutation, idempotency, and outbox behavior. Submit-decision writes now pass through one coordinator, one idempotency key, one command record, and one explicit state machine.

The synchronous request path does not return `COMMITTED_FULLY_ANCHORED`. It returns `COMMITTED_EVIDENCE_PENDING` only after local `SUCCESS` audit is recorded; external evidence remains asynchronous and reconciled. If local success evidence degrades after the business write, the command becomes `COMMITTED_DEGRADED` and the API returns or records `COMMITTED_EVIDENCE_INCOMPLETE`.

## Model

FDP-25 uses a pending command architecture for regulated mutation paths.

1. Submit command.
   - Require `X-Idempotency-Key`.
   - Create `regulated_mutation_commands`.
   - Set state `REQUESTED`.
   - Do not mutate business state.

2. Record audit intent.
   - Persist durable `ATTEMPTED` audit.
   - Set state `AUDIT_ATTEMPTED`.

3. Validate command.
   - Perform authorization, request validation, idempotency checks, and business validation against current state.
   - Idempotency conflict returns 409.
   - Missing idempotency key returns 400.

4. Apply business mutation.
   - Set state `BUSINESS_COMMITTING`.
   - Persist the alert decision and same-document outbox record.
   - Set state `BUSINESS_COMMITTED`.

5. Record success evidence.
   - Set state `SUCCESS_AUDIT_PENDING`.
   - Persist durable `SUCCESS` audit.
   - Set state `SUCCESS_AUDIT_RECORDED`.

6. Return pending external evidence state.
   - Set state `EVIDENCE_PENDING`.
   - Return `COMMITTED_EVIDENCE_PENDING`.

7. Degrade explicitly when success evidence fails after business write.
   - Set state `COMMITTED_DEGRADED`.
   - Persist response snapshot with `COMMITTED_EVIDENCE_INCOMPLETE`.
   - Record durable audit degradation.

## Required Statuses

- `REQUESTED`
- `AUDIT_ATTEMPTED`
- `BUSINESS_COMMITTING`
- `BUSINESS_COMMITTED`
- `SUCCESS_AUDIT_PENDING`
- `SUCCESS_AUDIT_RECORDED`
- `EVIDENCE_PENDING`
- `EVIDENCE_CONFIRMED`
- `COMMITTED`
- `COMMITTED_DEGRADED`
- `REJECTED`
- `FAILED`

Public request statuses remain:

- `REJECTED_BEFORE_MUTATION`
- `COMMITTED_EVIDENCE_PENDING`
- `COMMITTED_EVIDENCE_INCOMPLETE`

`COMMITTED_FULLY_ANCHORED` is async-only and must not be returned by the synchronous submit-decision request.

## Non-Goals

FDP-25 must not change scoring behavior, ML model behavior, Kafka event contracts, governance advisory semantics, model retraining, rollback automation, alert triggering, or SLA enforcement.

FDP-25 must not describe operator evidence as cryptographic proof unless the evidence is actually cryptographically verifiable. It is not distributed ACID, exactly-once Kafka, legal notarization, WORM storage, or perfect rollback.

## Required Tests

- `ATTEMPTED` audit unavailable -> no business state mutation is visible.
- business write failure -> `FAILED` command and `FAILED` audit.
- success audit unavailable after business write -> `COMMITTED_DEGRADED`, durable degradation, and no `COMMITTED_FULLY_ANCHORED`.
- committed command -> business mutation visible, local `SUCCESS` audit recorded, and domain outbox event persisted.
- replayed command idempotency -> stored response snapshot replayed without duplicate audit or duplicate domain outbox event.
- duplicate idempotency key with different payload -> 409.
- missing idempotency key -> 400.
- request path never returns `COMMITTED_FULLY_ANCHORED`.
- `AlertManagementService` does not call `AuditMutationRecorder`, audit service, or decision outbox writes for submit decision.
