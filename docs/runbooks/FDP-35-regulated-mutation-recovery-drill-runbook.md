# FDP-35 Regulated Mutation Recovery Drill Runbook

FDP-35 provides modeled restart/recovery proof in CI. It does not claim real OS/JVM/container kill chaos proof unless an explicit kill/restart test is added. True OS/JVM/container process termination chaos remains future scope unless explicitly implemented and run in CI.

## Ownership

- Owner: Fraud Platform On-call
- Approver: Fraud Platform Incident Lead
- Required authority: `regulated-mutation:recover` or `audit:verify` for read-only inspection
- Dual control: required for manual state repair, rollback approval, FDP-29 flag disablement, or renewal budget changes

## Escalation Clock

- T+5m: triage command state, lease status, public status, outbox, and audit phases
- T+15m: assign incident lead if recovery state remains ambiguous
- T+30m: rollback decision required if recovery backlog grows or API success ambiguity appears
- T+60m: post-incident review required

## Drill Checklist

- Identify command by approved operational lookup, preferably idempotency hash.
- Inspect state, execution status, lease expiry, lease renewal count, degradation reason, response snapshot presence, outbox record, and audit phases.
- Confirm recovery state wins over any stale response snapshot.
- Confirm no duplicate business mutation occurred.
- Record audit note with command state and action taken.
- Escalate if durable proof is ambiguous.

## Audit Record Template

- command reference: command id or idempotency hash only
- operator identity:
- reason code:
- action taken:
- evidence checked:
- approver identity:
- timestamp:
- no raw idempotency key:
- no raw payload:
- no customer-sensitive data:

## Forbidden Actions

- no manual business aggregate edit
- no raw idempotency key in ticket
- no lease owner rewrite
- no evidence confirmed without authoritative evidence
- no new idempotency key for the same mutation unless approved recovery says so
- no exposing stale response snapshot as committed success

## Post-Incident Validation

- recovery backlog stable or decreasing
- no false success responses
- no `FINALIZE_RECOVERY_REQUIRED` hidden
- dashboard green
- outbox ambiguity addressed
- no high-cardinality labels or log leaks

## Rollback Approval Matrix

| Action | Primary approver | Second approver | Required evidence |
| --- | --- | --- | --- |
| disable checkpoint renewal | Fraud Platform Incident Lead | SRE Lead | recovery backlog before/after and CI rollback validation |
| disable FDP-29 feature flags | Fraud Platform Owner | Compliance/Operations approver | proof that existing model-versioned commands remain visible |
| approve extended soak | SRE Lead | Fraud Platform Owner | dashboard thresholds stable |
| close incident | Incident Lead | Fraud Platform Owner | post-incident validation complete |

## Filled Sample Drill Output

Sample from local/Testcontainers drill, not production evidence.

```text
drill_id: FDP35-DRILL-2026-05-05-001
operator_identity: fraud-ops-admin
approver_identity: fraud-platform-incident-lead
command_reference: idempotency_hash=96e6f95f...
reason_code: FINALIZE_RECOVERY_REQUIRED
action_taken: inspected command, outbox, audit phases, and response snapshot
evidence_checked: command_state, public_status, outbox_records, success_audit_id
forbidden_actions_confirmed_not_taken: true
post_validation: backlog stable; no false success; dashboard green
timestamp: 2026-05-05T18:10:00Z
result: PASS
```
