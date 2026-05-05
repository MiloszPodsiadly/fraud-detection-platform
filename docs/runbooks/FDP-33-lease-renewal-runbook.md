# FDP-33 Lease Renewal Runbook

This runbook covers regulated mutation lease-renewal rejections. Renewal is an internal checkpoint mechanism only. It must not be invoked from public controllers, analyst APIs, or operator scripts.

## Common Rules

- Do not manually rewrite lease_owner.
- Do not manually extend expired leases.
- Do not mark evidence confirmed manually.
- Do not edit business aggregate directly.
- Do not submit a new idempotency key to bypass a stuck command.
- Do not disable fencing/renewal guards to clear backlog.

Required authority for any recovery action is fraud operations lead plus platform on-call. Bank-mode recovery also requires the bank-mode release owner.

## STALE_OWNER

Symptom: renewal rejection reason `STALE_OWNER`.
Likely impact: the worker is stale because another owner has claimed the command.
Safe operator action: confirm the newer owner and let the stale worker stop; allow current owner or normal recovery to proceed.
Forbidden action: Do not manually rewrite lease_owner.
Required authority: platform on-call for observation; fraud operations lead for recovery command handling.
Audit/log evidence to inspect: command document lease owner, lease expiry, attempt count, worker logs for old and new owner.
Metrics/dashboard to inspect: renewal rejected count by reason, lease takeover count, stale write rejection count.
Retry/rollback guidance: do not retry with the stale token; retry only through normal idempotent command replay.
Escalation criteria: repeated stale owner spikes in the same service window.

## EXPIRED_LEASE

Symptom: renewal rejection reason `EXPIRED_LEASE`.
Likely impact: the active worker missed its renewal window and the command is eligible for takeover.
Safe operator action: allow normal claim takeover or existing recovery flow.
Forbidden action: Do not manually extend expired leases.
Required authority: platform on-call; fraud operations lead if recovery is required.
Audit/log evidence to inspect: command lease expiry, current time, worker latency, Mongo latency, GC pauses.
Metrics/dashboard to inspect: expired lease rejection rate, lease remaining p50/p95/p99, takeover rate.
Retry/rollback guidance: do not retry renewal after expiry; replay through the regulated mutation coordinator if needed.
Escalation criteria: expired rejections correlate with infrastructure latency or service restarts.

## BUDGET_EXCEEDED

Symptom: renewal rejection reason `BUDGET_EXCEEDED` or `regulated_mutation_lease_renewal_budget_exceeded_total` increments.
Likely impact: FDP-33 prevented an infinite `PROCESSING` command. A direct budget exhaustion marks the command recovery-required.
Safe operator action: inspect the durable command state and follow the relevant recovery runbook.
Forbidden action: Do not disable fencing/renewal guards to clear backlog.
Required authority: fraud operations lead plus platform on-call; bank-mode release owner in bank mode.
Audit/log evidence to inspect: command state, execution status, degradation reason `LEASE_RENEWAL_BUDGET_EXCEEDED`, worker logs.
Metrics/dashboard to inspect: budget exceeded count, remaining budget histogram, capped renewal metrics, active processing age.
Retry/rollback guidance: do not increase budget during an incident without review; use normal recovery or replay.
Escalation criteria: any non-zero budget exceeded in production or bank mode.

## INVALID_EXTENSION

Symptom: renewal rejection reason `INVALID_EXTENSION`.
Likely impact: caller requested null, zero, or negative extension.
Safe operator action: treat as caller bug; inspect internal checkpoint caller.
Forbidden action: Do not retry with arbitrary larger extensions.
Required authority: platform on-call.
Audit/log evidence to inspect: worker checkpoint code path and service logs.
Metrics/dashboard to inspect: renewal rejected count by reason.
Retry/rollback guidance: retry only after fixing caller configuration or code.
Escalation criteria: repeated invalid extensions after deployment.

## COMMAND_NOT_FOUND

Symptom: renewal rejection reason `COMMAND_NOT_FOUND`.
Likely impact: caller used a claim token for a missing or deleted command.
Safe operator action: verify command id, idempotency key, and database.
Forbidden action: Do not submit a new idempotency key to bypass a stuck command.
Required authority: platform on-call.
Audit/log evidence to inspect: command lookup logs and Mongo collection state.
Metrics/dashboard to inspect: renewal rejected count by reason and command replay outcomes.
Retry/rollback guidance: replay only through the original idempotency key if the command should exist.
Escalation criteria: missing commands indicate data retention, database, or deployment mismatch.

## NON_RENEWABLE_STATE

Symptom: renewal rejection reason `NON_RENEWABLE_STATE`.
Likely impact: command is not in the explicit renewable table for its model version.
Safe operator action: inspect model version and state; allow normal replay or recovery.
Forbidden action: Do not manually change state to make renewal pass.
Required authority: fraud operations lead.
Audit/log evidence to inspect: command state transition history and worker logs.
Metrics/dashboard to inspect: renewal rejected count by reason and state.
Retry/rollback guidance: retry only if the command is reclaimed into a valid processing state by normal flow.
Escalation criteria: new worker code attempts renewal in unsupported states.

## TERMINAL_STATE

Symptom: renewal rejection reason `TERMINAL_STATE`.
Likely impact: the command has already reached a final or committed state.
Safe operator action: treat terminal state as authoritative.
Forbidden action: Do not mark evidence confirmed manually.
Required authority: fraud operations lead for any business-facing follow-up.
Audit/log evidence to inspect: command terminal state, success audit marker, outbox record if present.
Metrics/dashboard to inspect: renewal rejected count by reason and state.
Retry/rollback guidance: do not renew terminal commands; use read/replay behavior.
Escalation criteria: active workers keep heartbeating terminal commands.

## RECOVERY_STATE

Symptom: renewal rejection reason `RECOVERY_STATE`.
Likely impact: command requires recovery and renewal must not hide that state.
Safe operator action: follow the recovery runbook for the mutation model.
Forbidden action: Do not manually extend expired leases.
Required authority: fraud operations lead plus platform on-call.
Audit/log evidence to inspect: degradation reason, last error, recovery state, worker logs.
Metrics/dashboard to inspect: recovery-required command count and renewal rejected count.
Retry/rollback guidance: use recovery tooling, not renewal.
Escalation criteria: recovery-required backlog grows or repeats for the same operation.

## MODEL_VERSION_MISMATCH

Symptom: renewal rejection reason `MODEL_VERSION_MISMATCH`.
Likely impact: worker token and durable command model version disagree.
Safe operator action: stop the worker and inspect deployment/version skew.
Forbidden action: Do not edit business aggregate directly.
Required authority: platform on-call.
Audit/log evidence to inspect: claim token model version, command `mutation_model_version`, deployment version.
Metrics/dashboard to inspect: renewal rejected count by reason and model version.
Retry/rollback guidance: rollback the incompatible worker or replay with the correct executor path.
Escalation criteria: any production occurrence after rollout.

## EXECUTION_STATUS_MISMATCH

Symptom: renewal rejection reason `EXECUTION_STATUS_MISMATCH`.
Likely impact: command is not actively `PROCESSING`.
Safe operator action: respect current status; inspect whether command completed, failed, or is pending recovery.
Forbidden action: Do not manually rewrite execution status.
Required authority: fraud operations lead.
Audit/log evidence to inspect: command execution status and transition history.
Metrics/dashboard to inspect: renewal rejected count by reason and active processing age.
Retry/rollback guidance: do not renew; use normal claim, replay, or recovery.
Escalation criteria: worker attempts renewal before claim or after completion.

## UNKNOWN

Symptom: renewal rejection reason `UNKNOWN` in metrics.
Likely impact: unexpected reason input was normalized to protect metric cardinality.
Safe operator action: inspect application logs and recent code changes.
Forbidden action: Do not disable metric normalization.
Required authority: platform on-call.
Audit/log evidence to inspect: service logs around renewal rejection.
Metrics/dashboard to inspect: `UNKNOWN` reason count.
Retry/rollback guidance: fix reason mapping or caller bug before retrying.
Escalation criteria: any sustained `UNKNOWN` reason in production or bank mode.
