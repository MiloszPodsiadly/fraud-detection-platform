# FDP-25 Phase 1 - Regulated Mutation Coordinator

FDP-25 Phase 1 is the regulated mutation coordinator for the first migrated write path: `POST /api/v1/alerts/{alertId}/decision`.

This phase is a saga-style recoverable command state machine. It is not an ACID transaction boundary and it does not prove that a business mutation can never commit before success evidence exists.

FDP-24 remains intentionally narrower. FDP-24 provides external audit anchoring, fail-closed `ATTEMPTED` audit persistence, durable post-commit degradation detection, explicit `COMMITTED_EVIDENCE_PENDING` / `COMMITTED_EVIDENCE_INCOMPLETE` states, and recovery/reconciliation workflows. FDP-24 detects and exposes post-commit audit degradation. It does not eliminate it transactionally.

## Goal

Stop domain services from hand-assembling audit, business mutation, idempotency, and outbox behavior. Submit-decision writes now pass through one coordinator, one idempotency key, one command record, one lease/claim path, and one explicit state machine.

The synchronous request path does not return `COMMITTED_FULLY_ANCHORED`. It returns `COMMITTED_EVIDENCE_PENDING` only after local `SUCCESS` audit is recorded; external evidence remains asynchronous and reconciled. If local success evidence degrades after the business write, the command becomes `COMMITTED_DEGRADED` and the API returns or records `COMMITTED_EVIDENCE_INCOMPLETE`.

FDP-25 Phase 1 detects and exposes post-commit audit degradation. It does not eliminate that window transactionally.

## Model

FDP-25 Phase 1 uses a pending command architecture for regulated mutation paths.

1. Submit command.
   - Require `X-Idempotency-Key`.
   - Create `regulated_mutation_commands`.
   - Set state `REQUESTED`.
   - Do not mutate business state.
   - Set `executionStatus=NEW`.

2. Claim command execution.
   - Atomically claim by `idempotencyKey`, `requestHash`, `executionStatus`, and lease expiry.
   - Set `executionStatus=PROCESSING`, `leaseOwner`, `leaseExpiresAt`, `attemptCount`, and `lastHeartbeatAt`.
   - If another worker holds a non-expired lease, return HTTP 202 with `IN_PROGRESS`.

3. Record audit intent.
   - Persist durable `ATTEMPTED` audit with deterministic phase request id `regulated_command_id:ATTEMPTED`.
   - Set state `AUDIT_ATTEMPTED`.

4. Validate command.
   - Perform authorization, request validation, idempotency checks, and business validation against current state.
   - Idempotency conflict returns 409.
   - Missing idempotency key returns 400.

5. Apply business mutation.
   - Set state `BUSINESS_COMMITTING`.
   - Persist the alert decision and same-document outbox record.
   - Set state `BUSINESS_COMMITTED`.

6. Record success evidence.
   - Set state `SUCCESS_AUDIT_PENDING`.
   - Persist durable `SUCCESS` audit with deterministic phase request id `regulated_command_id:SUCCESS`.
   - Set state `SUCCESS_AUDIT_RECORDED`.

7. Return pending external evidence state.
   - Set state `EVIDENCE_PENDING`.
   - Set `executionStatus=COMPLETED`.
   - Return `COMMITTED_EVIDENCE_PENDING`.

8. Degrade explicitly when success evidence fails after business write.
   - Set state `COMMITTED_DEGRADED`.
   - Persist response snapshot with `COMMITTED_EVIDENCE_INCOMPLETE`.
   - Set `executionStatus=COMPLETED`.
   - Record durable audit degradation including command id, resource id, action, and reason.

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
- `IN_PROGRESS`
- `RECOVERY_REQUIRED`
- `COMMIT_UNKNOWN`
- `COMMITTED_EVIDENCE_PENDING`
- `COMMITTED_EVIDENCE_INCOMPLETE`

`COMMITTED_FULLY_ANCHORED` is async-only and must not be returned by the synchronous submit-decision request.

Client behavior:

- `IN_PROGRESS`: another execution owns the active lease; retry later with the same idempotency key.
- `RECOVERY_REQUIRED`: the command is in a known partial state and the platform will not guess a better result.
- `COMMIT_UNKNOWN`: business mutation may have started, but the command lacks enough durable evidence to report a final status.
- `COMMITTED_EVIDENCE_PENDING`: business mutation and local success audit completed; external evidence promotion remains pending.
- `COMMITTED_EVIDENCE_INCOMPLETE`: business mutation committed, but local success audit or evidence completion degraded.

The command store is authoritative for mutation lifecycle and public operation status. `AlertDocument` is the business aggregate. Outbox fields describe delivery state. Audit and external evidence describe proof state. Divergence between those layers must degrade to `RECOVERY_REQUIRED` or an evidence-incomplete state; APIs must not derive final operation status from the alert document alone.

## Recovery

The recovery service performs a bounded scan of stale command execution records. It may release safe pre-mutation commands back to `NEW`, retry only the missing success audit for `SUCCESS_AUDIT_PENDING` commands that already have a response snapshot, or mark unsafe partial states as `RECOVERY_REQUIRED`.

Recovery never re-runs the business mutation when the command state indicates the mutation may already have started.

Recovery is exposed only through authenticated operational surfaces:

- `POST /api/v1/regulated-mutations/recover` requires `regulated-mutation:recover`.
- `GET /api/v1/regulated-mutations/recovery/backlog` requires `regulated-mutation:recover` or `audit:verify`.
- `GET /api/v1/regulated-mutations/{idempotencyKey}` requires `regulated-mutation:recover` or `audit:verify`.

The command inspection endpoint is read-only and intentionally narrow. It exposes `idempotency_key`, `action`, `resource_type`, `resource_id`, `state`, `execution_status`, lease fields, `response_snapshot_present`, phase audit ids, degradation reason, last error, and update time. It does not expose command payloads, response bodies, notes, customer/account/card identifiers, or raw exception details.

The backlog response is bounded and includes `total_recovery_required`, `total_in_progress_expired`, `oldest_recovery_required_age`, `recovery_failed_terminal_count`, `repeated_recovery_failures`, `by_state`, and `by_action`.

Recovery semantics:

- `REQUESTED` and `AUDIT_ATTEMPTED` may be released to `NEW` for safe retry.
- `BUSINESS_COMMITTING` always becomes `RECOVERY_REQUIRED`; the platform does not infer success from an uncertain commit window.
- `BUSINESS_COMMITTED` reconstructs a response snapshot only from committed business state plus outbox evidence; otherwise it becomes `RECOVERY_REQUIRED`.
- `SUCCESS_AUDIT_PENDING` first binds an existing `regulated_command_id:SUCCESS` audit event and does not duplicate it. If none exists, it retries only the success audit phase.
- If success audit retry fails after the business mutation is visible, the command becomes `COMMITTED_DEGRADED`, a durable degradation event is recorded, and no `COMMITTED_FULLY_ANCHORED` status is returned.

Operational metrics:

- `regulated_mutation_recovery_required_count`
- `oldest_recovery_required_age_seconds`
- `recovery_failed_terminal_count`
- `repeated_recovery_failures_count`
- `regulated_mutation_recovery_outcome_total{outcome}`

System trust degrades when `regulated_mutation_recovery_required_count > 0`.

Mongo multi-document transactions are not required by FDP-25 Phase 1 and are not claimed as the guarantee. A future optional transaction mode may narrow local Mongo write windows, but the declared FDP-25 guarantee remains the recoverable command state machine and explicit degradation, not cross-system ACID.

## Non-Goals

FDP-25 Phase 1 must not change scoring behavior, ML model behavior, Kafka event contracts, governance advisory semantics, model retraining, rollback automation, alert triggering, or SLA enforcement.

FDP-25 Phase 1 must not describe operator evidence as cryptographic proof unless the evidence is actually cryptographically verifiable. It is not distributed ACID, exactly-once Kafka, legal notarization, WORM storage, transactional regulated commit, or perfect rollback.

## Required Tests

- `ATTEMPTED` audit unavailable -> no business state mutation is visible.
- business write failure -> `FAILED` command and `FAILED` audit.
- success audit unavailable after business write -> `COMMITTED_DEGRADED`, durable degradation, and no `COMMITTED_FULLY_ANCHORED`.
- committed command -> business mutation visible, local `SUCCESS` audit recorded, and domain outbox event persisted.
- replayed command idempotency -> stored response snapshot replayed without duplicate audit or duplicate domain outbox event.
- recovery binds an existing `regulated_command_id:SUCCESS` audit event without creating a duplicate success audit.
- duplicate idempotency key with different payload -> 409.
- missing idempotency key -> 400.
- request path never returns `COMMITTED_FULLY_ANCHORED`.
- `AlertManagementService` does not call `AuditMutationRecorder`, audit service, or decision outbox writes for submit decision.
- duplicate active request returns `IN_PROGRESS` without executing a second business mutation.
- unsafe partial replay returns `RECOVERY_REQUIRED` or `COMMIT_UNKNOWN`, not a generic 500 and not a duplicate mutation.
- `SUCCESS_AUDIT_PENDING` retry writes only missing success audit and never re-runs the business mutation.
- recovery service covers stale leases and crash-window states with bounded scans.
- recovery endpoints are backend-authorized; `regulated-mutation:recover` can trigger recovery, while `audit:verify` can read backlog only.
- command inspection endpoint returns command state without payload dump, returns 404 for missing commands, and is forbidden to ordinary analysts.
- outbox unknown-confirmation reconciliation requires `X-Idempotency-Key`, records `ATTEMPTED` before mutation, is duplicate replay safe, and records durable degradation instead of claiming fully anchored evidence when success audit fails.

## FDP-26 Candidate Work

FDP-25 remains a recoverable saga coordinator. FDP-26 should evaluate a stricter regulated mutation commit model for additional write paths and, where infrastructure supports it, tighter local transaction boundaries. Any future claim that a business mutation cannot become visible before all required success evidence exists must be implemented at the concrete storage/evidence boundary and tested as such.
