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

Required authority for recovery action is fraud operations lead plus platform on-call. Bank-mode recovery also requires the bank-mode release owner.

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
