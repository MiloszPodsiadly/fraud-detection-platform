# Fraud Case Lifecycle Idempotency Runbook

Status: FDP-44 operational runbook.

## Scope

Fraud-case lifecycle idempotency is local to alert-service lifecycle mutations. It applies to create, assign, note,
decision, transition, close, reopen, and fraud-case update HTTP mutation paths that require `X-Idempotency-Key`.

This runbook does not claim global exactly-once, distributed ACID, Kafka/outbox exactly-once, FDP-29 external
finality, lease fencing, WORM storage, legal notarization, bank certification, or deterministic concurrent response
ordering.

## Expected Behavior

- Every public lifecycle mutation request must send `X-Idempotency-Key`.
- Same key, same resolved actor, same action, same scope, and same payload replays the stored response snapshot.
- Same key with a different actor, action, scope, or payload returns an idempotency conflict and does not mutate.
- Concurrent same-key calls do not create a duplicate mutation/audit/idempotency record inside the active local
  idempotency window. Identical concurrent response timing is not guaranteed; the loser may receive replay or
  `IDEMPOTENCY_KEY_IN_PROGRESS`.
- Idempotency records are retained for `app.fraud-cases.idempotency.retention`. After retention and eventual Mongo TTL
  cleanup, retry with the same key may execute as a new lifecycle operation.
- Response snapshots are explicit replay DTOs. Raw idempotency keys, request hashes, lease owners, stack traces, and
  raw exception text must not be stored in the replay snapshot or returned in idempotency errors.

## Triage

1. Check `fraud_case_lifecycle_idempotency_total` by `outcome`.
2. For spikes in `in_progress` or `race_resolved`, verify whether clients are retrying the same key concurrently.
3. For `conflict`, compare client behavior against the same-key same-claim rule. Do not expose raw keys in support
   tickets; use client correlation data and server logs with normal access controls.
4. For `snapshot_too_large`, treat the mutation as failed closed. The lifecycle mutation and audit append must not be
   considered committed by this signal alone.
5. For persistence failures that are not known same-key duplicate/write-conflict races, do not classify them as replay.

## Recovery Rules

- Do not manually edit `fraud_case_lifecycle_idempotency_records`.
- Do not delete TTL records to force a retry while client requests may still be in flight.
- Do not reuse an idempotency key for a different lifecycle claim.
- If a client receives `IDEMPOTENCY_KEY_IN_PROGRESS`, retry later with the same key and same request.
- If retention has elapsed and TTL cleanup removed the record, the old key no longer proves replay safety.

## Escalation

Open a NO-GO release item if any of these happen:

- Public lifecycle mutation path accepts a missing idempotency key.
- Same key different claim mutates or appends audit.
- Same key same claim creates a second mutation, audit entry, or idempotency record inside retention.
- Unknown `DataAccessException` or `TransactionSystemException` is converted into replay/in-progress.
- Documentation or dashboards must not claim global exactly-once, external finality, lease fencing, or deterministic
  concurrent response timing.
