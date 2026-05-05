# FDP-34 Safe Checkpoint Renewal Runbook

This runbook covers failures from FDP-34 explicit checkpoint renewal inside regulated mutation executors. Checkpoint renewal is internal worker behavior only. It is not an operator API, public heartbeat endpoint, or proof of business progress.

Renewal preserves ownership, not progress. Checkpoint failure means worker must stop. Budget exceeded means operational recovery/fail-closed, not success. FDP-34 does not provide distributed lock, external finality, process-kill chaos proof, or FDP-29 production enablement.

## Common Rules

- Do not manually edit AlertDocument business decision.
- Do not submit a new idempotency key to "fix" command.
- Do not rewrite leaseOwner.
- Do not mark evidence confirmed manually.
- Do not increase lease/renewal budgets blindly in production.
- do not increase lease budget blindly.
- Do not bypass checkpoint renewal service.
- Do not treat renewal as progress.
- Never treat renewal as audit attempt, business progress, evidence confirmation, external finality, or success.

Required authority for recovery action is fraud operations lead plus platform on-call. Bank-mode recovery also requires the bank-mode release owner.

Approved checkpoints covered by this runbook: `BEFORE_ATTEMPTED_AUDIT`, `BEFORE_LEGACY_BUSINESS_COMMIT`, `BEFORE_SUCCESS_AUDIT_RETRY`, `BEFORE_EVIDENCE_PREPARATION`, `AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE`, and `BEFORE_EVIDENCE_GATED_FINALIZE`.

## Per-Reason Actions

| Reason | Symptom | Likely impact | Safe operator action | Forbidden action | Required authority / approval | Audit requirement | Retry / rollback guidance | Metrics to inspect | Trust incident | Escalation path |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `STALE_OWNER` | checkpoint rejects because durable owner differs from token | worker is no longer authorized; another owner may continue | inspect command owner, expiry, takeover timeline, stale write counters | do not rewrite leaseOwner or retry stale token | platform on-call | record operator review if manual intervention is considered | let current owner/replay/recovery proceed | checkpoint renewal failed/blocked, stale write rejected, lease takeover | open if repeated in bank/prod or after finality work started | regulated mutation owner |
| `EXPIRED_LEASE` | checkpoint rejects because lease is not after now | command may be claimable by another worker | inspect GC pauses, dependency latency, lease budget | do not manually extend expired lease | platform on-call | record if production command is touched | allow normal takeover; retry original idempotency only | checkpoint failed, lease takeover, transition latency | open if spike affects regulated decisions | platform incident lead |
| `BUDGET_EXCEEDED` | checkpoint renewal exhausts count or total duration | durable recovery/fail-closed posture; no success | inspect renewal count, budget start, slow dependency, Mongo latency | do not increase budgets blindly in production | fraud ops lead + platform on-call; bank release owner in bank mode | audit recovery decision and approval | use approved recovery; do not force success | budget exceeded, no_progress, transition latency | always open for bank/prod non-zero | regulated mutation owner and SRE |
| `NON_RENEWABLE_STATE` | state/checkpoint pair is not policy-approved | checkpoint placement or replay state mismatch | inspect state machine and executor logs | do not add ad hoc checkpoint call | code owner approval before code change | audit if production data is inspected | stop worker; use replay/recovery | blocked by reason, state transition metrics | open if it occurs in production | owning engineer |
| `TERMINAL_STATE` | command is finalized, rejected, committed, or otherwise terminal | renewal would imply false work after terminal | verify final state and response snapshot | do not reopen by direct DB edit | fraud ops lead for any manual replay | record terminal verification if user-facing | replay existing result only | blocked terminal, replay metrics | open if terminal state conflicts with business aggregate | incident commander |
| `RECOVERY_STATE` | execution status or state is recovery-required | recovery state wins over progress-looking fields | inspect degradation reason and recovery runbook | do not mark success or evidence confirmed | fraud ops lead + platform on-call | record recovery decision | follow recovery path; original idempotency only | recovery-required counters, blocked reason | open if recovery blocks bank SLA | regulated mutation owner |
| `MODEL_VERSION_MISMATCH` | claim token model differs from durable command model | deployment or stale token skew | inspect deployment version and command model | do not mutate model version manually | platform on-call + service owner | record deployment correlation | stop stale worker; replay after deployment stabilizes | blocked/rejected reason, deploy timeline | open if concurrent model versions corrupt flow | release owner |
| `EXECUTION_STATUS_MISMATCH` | command is not `PROCESSING` while checkpoint runs | stale worker or invalid executor path | inspect claim path and transition history | do not force status to PROCESSING | platform on-call | audit if manual action considered | normal replay/recovery only | blocked reason, stale write rejected | open if repeated after deployment | owning engineer |
| `UNSUPPORTED_CHECKPOINT` | caller used null or unreviewed checkpoint | code defect or test-only path leaked | inspect source and architecture guard | do not bypass policy table | code owner approval | no data edit without audit trail | deploy fix; no operator success marking | blocked reason, architecture test result | open if production executor can call it | engineering lead |
| `UNKNOWN` | reason normalized to unknown | unexpected exception or unmapped reason | inspect stack trace without adding raw error to metric labels | do not classify as audit degradation by default | platform on-call | audit investigation notes | stop worker; use recovery only after classification | unknown reason counters, logs | open if any bank/prod occurrence | incident commander |

## STALE_OWNER

Symptom: checkpoint renewal rejects with `STALE_OWNER` because the durable `leaseOwner` no longer matches the worker token.
Impact: the current worker is stale and must stop; another owner or normal recovery may continue.
Immediate safe operator action: inspect command state, lease owner, lease expiry, renewal count, takeover timeline, and stale write counters.
Forbidden action: do not rewrite `leaseOwner`, retry the stale token, submit a new idempotency key, or edit `AlertDocument`.
Required authority / approval: platform on-call; fraud operations lead if customer-facing recovery is considered.
Audit requirement: record the operator review before any manual recovery decision.
Retry / rollback guidance: allow the current owner, replay, or approved recovery path to proceed with the original idempotency key.
Metrics to inspect: `regulated_mutation_checkpoint_renewal_blocked_total`, `regulated_mutation_stale_write_rejected_total`, and `regulated_mutation_lease_takeover_total`.
When to open trust incident: open for repeated bank/prod occurrences or if stale ownership appears after finality work started.
Escalation path: regulated mutation owner, then incident commander for bank/prod impact.

## EXPIRED_LEASE

Symptom: checkpoint renewal rejects with `EXPIRED_LEASE` because durable `lease_expires_at` is not after the service clock.
Impact: the command may be claimable by another worker; the expired worker must stop.
Immediate safe operator action: inspect dependency latency, JVM pauses, Mongo latency, lease duration, and renewal budget.
Forbidden action: do not manually extend expired leases, rewrite owner fields, or bypass checkpoint renewal.
Required authority / approval: platform on-call; bank release owner if production budget changes are proposed.
Audit requirement: record any production command inspection or recovery decision.
Retry / rollback guidance: allow normal takeover or approved recovery; do not retry with a new idempotency key.
Metrics to inspect: checkpoint failed/blocked, lease takeover, transition latency, and long-running `PROCESSING`.
When to open trust incident: open on spikes affecting regulated decisions or any bank-mode customer impact.
Escalation path: platform incident lead, regulated mutation owner, SRE.

## BUDGET_EXCEEDED

Symptom: checkpoint renewal rejects with `BUDGET_EXCEEDED` because count or total duration budget is exhausted.
Impact: execution must stop immediately; FDP-33 durable recovery/fail-closed state is authoritative.
Immediate safe operator action: inspect `lease_renewal_count`, `lease_budget_started_at`, `lease_expires_at`, degradation reason, slow dependency logs, and Mongo latency.
Forbidden action: do not increase budgets blindly in prod/bank, mark success manually, edit `AlertDocument`, rewrite `leaseOwner`, or classify this as audit degradation.
Required authority / approval: fraud operations lead plus platform on-call; bank release owner in bank mode.
Audit requirement: audit the recovery decision and the approval trail.
Retry / rollback guidance: use approved recovery with the original idempotency key; rollback by disabling checkpoint adoption or renewal config safely if the release is at fault.
Metrics to inspect: budget exceeded, checkpoint no-progress, checkpoint renewal blocked, transition latency, and long-running `PROCESSING`.
When to open trust incident: always open for non-zero bank/prod `BUDGET_EXCEEDED`.
Escalation path: regulated mutation owner, SRE, bank release owner, incident commander.

## NON_RENEWABLE_STATE

Symptom: checkpoint renewal rejects with `NON_RENEWABLE_STATE` because state/checkpoint pair is not policy-approved.
Impact: executor placement or replay state does not match the reviewed safe checkpoint table.
Immediate safe operator action: inspect state machine history, executor logs, deployment version, and the named checkpoint.
Forbidden action: do not add ad hoc checkpoint calls, bypass policy, or force command status to `PROCESSING`.
Required authority / approval: code owner approval before any code change; platform on-call for production incident triage.
Audit requirement: record production data inspection and incident notes.
Retry / rollback guidance: stop the worker and use replay/recovery; deploy a reviewed fix if checkpoint placement is wrong.
Metrics to inspect: checkpoint blocked by reason, state transition metrics, stale write rejected, and deployment timeline.
When to open trust incident: open if this occurs in production or blocks regulated decisions.
Escalation path: owning engineer, regulated mutation owner, release owner.

## TERMINAL_STATE

Symptom: checkpoint renewal rejects with `TERMINAL_STATE` for finalized, committed, rejected, or otherwise terminal state.
Impact: renewal after terminal state would falsely imply work continues after final state.
Immediate safe operator action: verify final command state, public status, response snapshot, outbox status, and audit evidence.
Forbidden action: do not reopen by direct DB edit, mark evidence confirmed manually, or submit a replacement idempotency key.
Required authority / approval: fraud operations lead for any manual user-facing action.
Audit requirement: record terminal-state verification if manual communication or recovery is needed.
Retry / rollback guidance: replay the existing result only; use formal incident recovery if aggregate and command disagree.
Metrics to inspect: checkpoint blocked terminal, replay metrics, outbox publication, and audit persistence metrics.
When to open trust incident: open if terminal command state conflicts with the business aggregate or audit evidence.
Escalation path: incident commander, regulated mutation owner, fraud operations lead.

## RECOVERY_STATE

Symptom: checkpoint renewal rejects with `RECOVERY_STATE` because state or execution status is recovery-required.
Impact: recovery state wins over progress-looking fields, including response snapshots.
Immediate safe operator action: inspect degradation reason, command state, lease metadata, and recovery runbook.
Forbidden action: do not mark success, mark evidence confirmed, rewrite owner fields, or edit `AlertDocument`.
Required authority / approval: fraud operations lead plus platform on-call.
Audit requirement: audit recovery decision and any operator action.
Retry / rollback guidance: follow recovery path with original idempotency key; do not use checkpoint renewal to escape recovery.
Metrics to inspect: recovery-required counters, checkpoint blocked reason, no-progress, and stale write rejected.
When to open trust incident: open if recovery blocks bank SLA or impacts finality-sensitive decisions.
Escalation path: regulated mutation owner, fraud operations lead, incident commander.

## MODEL_VERSION_MISMATCH

Symptom: checkpoint renewal rejects with `MODEL_VERSION_MISMATCH` because token model and durable command model differ.
Impact: deployment skew or stale token may be routing the command through the wrong executor assumptions.
Immediate safe operator action: inspect deployment timeline, command model version, token-producing worker version, and executor routing.
Forbidden action: do not manually mutate model version or force a different executor path.
Required authority / approval: platform on-call plus service owner; release owner for deployment rollback.
Audit requirement: record deployment correlation and any command inspection.
Retry / rollback guidance: stop stale workers, stabilize deployment, then use replay/recovery.
Metrics to inspect: checkpoint blocked by model mismatch, executor routing failures, deployment health, and stale owner metrics.
When to open trust incident: open if concurrent versions may have corrupted regulated flow.
Escalation path: release owner, regulated mutation owner, incident commander.

## EXECUTION_STATUS_MISMATCH

Symptom: checkpoint renewal rejects with `EXECUTION_STATUS_MISMATCH` because durable status is not `PROCESSING`.
Impact: worker is stale or checkpoint call is outside the claim-owned execution window.
Immediate safe operator action: inspect claim history, transition history, and the current execution status.
Forbidden action: do not force status to `PROCESSING`, bypass fencing, or retry with a new idempotency key.
Required authority / approval: platform on-call; code owner if executor placement is suspect.
Audit requirement: audit manual inspection and recovery decisions.
Retry / rollback guidance: rely on replay/recovery only; do not use renewal to recreate ownership.
Metrics to inspect: checkpoint blocked, stale write rejected, transition conflicts, and lease takeover.
When to open trust incident: open if repeated after deployment or in bank mode.
Escalation path: owning engineer, regulated mutation owner, platform incident lead.

## UNSUPPORTED_CHECKPOINT

Symptom: checkpoint renewal rejects with `UNSUPPORTED_CHECKPOINT` for null or unreviewed checkpoint.
Impact: code defect or test-only path may have escaped the safe checkpoint table.
Immediate safe operator action: inspect source version, architecture guard results, and executor call path.
Forbidden action: do not bypass policy table or turn renewal into a generic heartbeat.
Required authority / approval: code owner approval before code change; release owner for hotfix deployment.
Audit requirement: no data edit without audit trail; record production investigation.
Retry / rollback guidance: stop affected worker path and deploy a reviewed fix; do not mark success manually.
Metrics to inspect: unsupported checkpoint counters, blocked checkpoint metrics, CI architecture tests.
When to open trust incident: open if any production executor can call unsupported checkpoint.
Escalation path: engineering lead, regulated mutation owner, incident commander.

## UNKNOWN

Symptom: checkpoint renewal emits `UNKNOWN` because reason normalization could not classify the failure.
Impact: behavior is unclassified and must be treated as fail-closed until understood.
Immediate safe operator action: inspect logs, stack trace, command state, lease metadata, and deployment changes without adding raw errors to metric labels.
Forbidden action: do not classify as audit degradation by default, mark success, edit `AlertDocument`, or bypass renewal service.
Required authority / approval: platform on-call; incident commander for bank/prod.
Audit requirement: audit investigation notes and any manual recovery decision.
Retry / rollback guidance: stop worker; use recovery only after reason is classified or release is rolled back.
Metrics to inspect: unknown reason counters, checkpoint blocked/failed, transition latency, and deployment health.
When to open trust incident: open for any bank/prod occurrence.
Escalation path: incident commander, regulated mutation owner, SRE.

## Checkpoint renewal failed before business mutation

Symptom: checkpoint rejection before legacy business commit, usually at `BEFORE_LEGACY_BUSINESS_COMMIT`.
Impact: the worker must stop before `command.mutation().execute` and before any business aggregate write.
Safe operator action: inspect command state, lease owner, lease expiry, renewal reason, attempted audit marker, and worker logs.
Forbidden action: do not manually edit business aggregate, do not submit a new idempotency key, do not manually rewrite leaseOwner, and do not bypass checkpoint renewal.
Retry guidance: replay only through the original idempotency key or approved recovery path after ownership/recovery state is clear.
Escalation: repeated failures before business mutation indicate lease budget, dependency latency, or executor checkpoint placement issues.

## Checkpoint renewal failed before FDP-29 finalize

Symptom: checkpoint rejection at `AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE` or `BEFORE_EVIDENCE_GATED_FINALIZE`.
Impact: the worker must stop before local finalize transaction, public finality transition, outbox write, success audit write, or evidence-confirmed projection.
Safe operator action: inspect evidence-prepared state, finalizing state, lease metadata, public status, local commit marker, audit chain status, and outbox records.
Forbidden action: do not mark evidence confirmed, do not manually rewrite leaseOwner, do not bypass checkpoint renewal, and do not submit a new idempotency key.
Retry guidance: allow normal claim/replay/recovery to resolve finality; do not force final status by direct database edit.
Escalation: any production occurrence before finalize requires regulated mutation owner review.

## Worker renewing but not progressing

Symptom: command lease renews at checkpoints but durable command state does not advance.
Impact: Renewal preserves ownership, not progress. A renewing worker can still be stuck before a later durable transition.
Safe operator action: inspect command timeline, checkpoint metrics, transition metrics, audit/outbox status, dependency latency, and worker logs around the named checkpoint.
Forbidden action: do not treat renewal success as business success, do not edit business aggregate directly, do not mark evidence confirmed, and do not bypass checkpoint renewal.
Retry guidance: allow renewal budget to fail closed or use approved recovery; do not add ad hoc heartbeat calls.
Escalation: repeated no-progress renewal indicates implementation defect or dependency stall.

## Prod/bank Drill Checklist

- simulate `STALE_OWNER`
- simulate `BUDGET_EXCEEDED`
- verify dashboards and alerts for checkpoint failure/no-progress, long-running `PROCESSING`, budget exceeded, and stale/expired spikes
- verify operator can inspect command state, lease owner, lease expiry, renewal count, and degradation reason
- verify rollback plan to disable checkpoint adoption or renewal config safely
- record drill outcome, approval trail, gaps, and remediation owner
