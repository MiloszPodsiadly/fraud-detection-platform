# FDP-25 Regulated Mutation Commit Model

FDP-25 is the planned architecture for regulated mutation commit semantics.

FDP-24 remains intentionally narrower. FDP-24 provides external audit anchoring, fail-closed `ATTEMPTED` audit persistence, durable post-commit degradation detection, explicit `COMMITTED_EVIDENCE_PENDING` / `COMMITTED_EVIDENCE_INCOMPLETE` states, and recovery/reconciliation workflows. FDP-24 detects and exposes post-commit audit degradation. It does not eliminate it transactionally.

## Goal

Eliminate the class of failure where a business mutation becomes visible and the required `SUCCESS` audit evidence cannot be completed.

In regulated mode, no business mutation is visible unless audit evidence preconditions are satisfied.

## Model

FDP-25 should use a pending command architecture for regulated mutation paths.

1. Submit command.
   - Create `PendingMutationCommand`.
   - Set status `PENDING_AUDIT`.
   - Do not mutate business state.

2. Anchor command attempt evidence.
   - Persist and externally anchor `ATTEMPTED` command evidence.

3. Validate command.
   - Perform authorization, request validation, idempotency checks, and business validation against current state.
   - Validation failure sets `FAILED_BUSINESS_VALIDATION`.

4. Prepare success evidence path.
   - Ensure the required durable audit and external anchor path for `SUCCESS` evidence is available before business mutation is made visible.

5. Apply business mutation.
   - Apply the domain state change only after required evidence preconditions pass.
   - Emit the domain outbox event in the same durable write boundary as the mutation where the local datastore supports it.

6. Commit command.
   - Mark command `COMMITTED`.

7. Reject unavailable evidence.
   - If audit evidence cannot be completed, set command status `REJECTED_AUDIT_EVIDENCE_UNAVAILABLE`.
   - No business mutation is applied.

## Required Statuses

- `PENDING_AUDIT`
- `AUDIT_READY`
- `COMMITTED`
- `REJECTED_AUDIT_EVIDENCE_UNAVAILABLE`
- `FAILED_BUSINESS_VALIDATION`

## Non-Goals

FDP-25 must not change scoring behavior, ML model behavior, Kafka event contracts, governance advisory semantics, model retraining, rollback automation, alert triggering, or SLA enforcement.

FDP-25 must not describe operator evidence as cryptographic proof unless the evidence is actually cryptographically verifiable.

## Required Tests

- `SUCCESS` audit evidence unavailable -> no business state mutation is visible.
- command validation failure -> `FAILED_BUSINESS_VALIDATION`, no business mutation.
- evidence unavailable -> `REJECTED_AUDIT_EVIDENCE_UNAVAILABLE`, no domain outbox event.
- committed command -> business mutation visible and domain outbox event persisted.
- replayed command idempotency -> no duplicate mutation or duplicate domain outbox event.
- `COMMITTED` is never reached without required audit evidence preconditions.
