# FDP-34 Safe Checkpoint Renewal Runbook

This runbook covers failures from FDP-34 explicit checkpoint renewal inside regulated mutation executors. Checkpoint renewal is internal worker behavior only. It is not an operator API, public heartbeat endpoint, or proof of business progress.

## Common Rules

- Do not manually edit business aggregate.
- Do not submit a new idempotency key.
- Do not manually rewrite lease_owner.
- Do not mark evidence confirmed.
- Do not bypass checkpoint renewal.
- Do not increase lease budget blindly in bank/prod.
- Do not disable fencing or checkpoint guards to clear backlog.

Required authority for recovery action is fraud operations lead plus platform on-call. Bank-mode recovery also requires the bank-mode release owner.

## Checkpoint renewal failed before business mutation

Symptom: checkpoint rejection before legacy business commit, usually at `AFTER_ATTEMPTED_AUDIT` or `BEFORE_LEGACY_BUSINESS_COMMIT`.
Impact: the worker must stop before `command.mutation().execute` and before any business aggregate write.
Safe operator action: inspect command state, lease owner, lease expiry, renewal reason, attempted audit marker, and worker logs.
Forbidden action: do not manually edit business aggregate, do not submit a new idempotency key, do not manually rewrite lease_owner, and do not bypass checkpoint renewal.
Retry guidance: replay only through the original idempotency key or approved recovery path after ownership/recovery state is clear.
Escalation: repeated failures before business mutation indicate lease budget, dependency latency, or executor checkpoint placement issues.

## Checkpoint renewal failed before FDP-29 finalize

Symptom: checkpoint rejection at `AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE` or `BEFORE_EVIDENCE_GATED_FINALIZE`.
Impact: the worker must stop before local finalize transaction, public finality transition, outbox write, success audit write, or evidence-confirmed projection.
Safe operator action: inspect evidence-prepared state, finalizing state, lease metadata, public status, local commit marker, audit chain status, and outbox records.
Forbidden action: do not mark evidence confirmed, do not manually rewrite lease_owner, do not bypass checkpoint renewal, and do not submit a new idempotency key.
Retry guidance: allow normal claim/replay/recovery to resolve finality; do not force final status by direct database edit.
Escalation: any production occurrence before finalize requires regulated mutation owner review.

## Budget exceeded at checkpoint

Symptom: checkpoint renewal returns or records `BUDGET_EXCEEDED`.
Impact: FDP-33 budget-exceeded recovery remains authoritative. The worker must stop and must not mutate business, evidence, audit, outbox, or command transition fields after the failed checkpoint.
Safe operator action: inspect slow dependency, Mongo latency, audit contention, outbox latency, active processing age, renewal count, and budget started time.
Forbidden action: do not increase lease budget blindly in bank/prod, do not manually extend the lease, do not manually rewrite lease_owner.
Retry guidance: use approved recovery or original idempotent replay after the recovery posture is understood.
Escalation: any non-zero budget exceeded in bank/prod is an incident signal.

## Worker renewing but not progressing

Symptom: command lease renews at checkpoints but durable command state does not advance.
Impact: Renewal preserves ownership, not progress. A renewing worker can still be stuck before a later durable transition.
Safe operator action: inspect command timeline, checkpoint metrics, transition metrics, audit/outbox status, dependency latency, and worker logs around the named checkpoint.
Forbidden action: do not treat renewal success as business success, do not edit business aggregate directly, do not mark evidence confirmed, and do not bypass checkpoint renewal.
Retry guidance: allow renewal budget to fail closed or use approved recovery; do not add ad hoc heartbeat calls.
Escalation: repeated no-progress renewal indicates implementation defect or dependency stall.

## Stale owner / expired lease at checkpoint

Symptom: checkpoint renewal rejects with `STALE_OWNER` or `EXPIRED_LEASE`.
Impact: the worker is no longer authorized to continue. Another owner may already hold or acquire the command.
Safe operator action: confirm durable lease owner/expiry, current command state, takeover metrics, and stale write rejection metrics.
Forbidden action: do not manually rewrite lease_owner, do not manually extend expired leases, do not retry using the stale token.
Retry guidance: let normal takeover, replay, or recovery proceed.
Escalation: spikes indicate worker latency, GC pauses, infrastructure stalls, or too-short lease budget.
