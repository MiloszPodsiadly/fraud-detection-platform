# FDP-35 Regulated Mutation Recovery Drill Runbook

FDP-35 provides modeled restart/recovery proof in CI. It verifies durable post-crash command states, replay policy, recovery API behavior, and operator visibility. It does not claim real OS/JVM/container process-kill chaos unless an explicit real-chaos job is implemented and run.

True OS/JVM/container termination chaos remains future scope unless explicitly implemented.

FDP-36 implements selected real Docker/container kill-restart proof separately in
`docs/runbooks/FDP-36-real-chaos-recovery-drill-runbook.md`. FDP-35 remains the
modeled restart/recovery runbook and must not be reworded as real process-kill
proof.

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

## Reason-Specific Operator Actions

| Reason | What it means | Operator action | Escalate when | Forbidden action |
| --- | --- | --- | --- | --- |
| `STALE_OWNER` | Worker no longer owns the command lease. | Inspect current owner hash, command state, and latest transition; allow current owner/recovery path to proceed. | Same command repeatedly rejects current workers. | Do not rewrite `lease_owner`. |
| `EXPIRED_LEASE` | Lease expired before a claimed transition. | Confirm no business result was exposed; check takeover/recovery backlog. | Expired commands accumulate for more than 10 minutes. | Do not mark success from stale worker output. |
| `BUDGET_EXCEEDED` | Lease renewal/checkpoint budget is exhausted. | Treat as explicit recovery/no-progress; inspect `degradation_reason` and recovery backlog. | More than 3 in 5 minutes or any customer-visible ambiguity. | Do not increase budget without dual approval. |
| `NON_RENEWABLE_STATE` | Command is in a state that must not renew. | Confirm terminal/recovery state and route to recovery or closure. | State is unexpected for model version. | Do not renew manually. |
| `TERMINAL_STATE` | Command is already terminal. | Verify no duplicate operation is started; use replay result only. | Terminal state conflicts with business aggregate. | Do not mutate terminal command fields. |
| `RECOVERY_STATE` | Command already requires recovery. | Keep recovery visible; follow recovery API/runbook. | Response snapshot appears newer than recovery state. | Do not replay stale snapshot as success. |
| `MODEL_VERSION_MISMATCH` | Worker/model version does not match stored command. | Inspect command model version and route to matching executor/recovery owner. | Mismatch occurs on new commands. | Do not downgrade `mutation_model_version`. |
| `EXECUTION_STATUS_MISMATCH` | Expected execution status no longer matches. | Re-read command, verify current status, and retry only through normal coordinator path. | Status oscillates or hides recovery. | Do not force status to `COMPLETED`. |
| `UNSUPPORTED_CHECKPOINT` | Renewal attempted at an unapproved checkpoint. | Stop worker path; inspect policy and command state. | Appears in production readiness CI or more than once in operations. | Do not add ad hoc checkpoint names. |
| `FINALIZE_RECOVERY_REQUIRED` | FDP-29 finalize path requires operator recovery. | Verify local evidence/outbox/audit state and external confirmation status. | External evidence is ambiguous. | Do not report evidence confirmed. |
| `RECOVERY_REQUIRED` | Legacy or generic recovery is required. | Inspect command, outbox, audit phases, and business aggregate consistency. | Snapshot/business state conflict exists. | Do not return committed success. |
| `OUTBOX_CONFIRMATION_UNKNOWN` | Business state may be committed but broker confirmation is unknown. | Use decision outbox reconciliation; verify broker offset/evidence before resolving. | Broker evidence cannot be obtained within 30 minutes. | Do not republish blindly with a new idempotency key. |

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
