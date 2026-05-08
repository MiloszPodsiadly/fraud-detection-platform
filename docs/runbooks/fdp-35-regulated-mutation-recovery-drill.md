# FDP-35 Regulated Mutation Recovery Drill

This operator drill is for regulated mutation recovery. It uses sample values only and contains no secrets.

FDP-35 provides modeled restart/recovery proof in CI. It verifies durable post-crash command states, replay policy, recovery API behavior, and operator visibility. It does not claim real OS/JVM/container process-kill chaos unless an explicit real-chaos job is implemented and run.

True OS/JVM/container termination chaos remains future scope unless explicitly implemented.

## Operator Control

- Owner: Fraud Platform On-call.
- Approver: Fraud Platform Incident Lead.
- Required authority: `regulated-mutation:recover` or `audit:verify`.
- Dual-control requirement: manual state repair, rollback approval, FDP-29 flag disablement, and lease budget changes require a second approver.
- Escalation: T+5m triage, T+15m incident lead, T+30m rollback decision, T+60m post-incident review.

## Audit Record Template

- command reference: command id or idempotency hash only
- operator identity:
- reason code:
- action taken:
- evidence checked:
- approver identity:
- timestamp:
- confirm no raw idempotency key, raw payload, or customer-sensitive data:

## Forbidden Actions

- no manual business aggregate edit
- no raw idempotency key in ticket
- no lease owner rewrite
- no evidence confirmed without authoritative evidence
- no new idempotency key for the same mutation unless approved recovery says so

## Post-Incident Validation

- recovery backlog stable/decreasing
- no false success responses
- no `FINALIZE_RECOVERY_REQUIRED` hidden
- dashboard green
- outbox ambiguity addressed
- no high-cardinality labels/log leaks

## Rollback Approval Matrix

| Action | Approver | Second approver |
| --- | --- | --- |
| disable checkpoint renewal | Fraud Platform Incident Lead | SRE Lead |
| disable FDP-29 feature flags | Fraud Platform Owner | Compliance/Operations approver |
| approve extended soak | SRE Lead | Fraud Platform Owner |
| close incident | Incident Lead | Fraud Platform Owner |

## Reason-Specific Drill Matrix

| Reason | Required drill evidence | Expected result | Escalation |
| --- | --- | --- | --- |
| `STALE_OWNER` | current lease owner hash, stale rejection metric, command state | stale worker cannot transition command | repeated stale rejections |
| `EXPIRED_LEASE` | lease expiry, takeover/recovery state, API response | no committed success from expired worker | expired backlog over threshold |
| `BUDGET_EXCEEDED` | lease renewal count, degradation reason, recovery response | explicit recovery/no-progress | more than 3 in 5 minutes |
| `NON_RENEWABLE_STATE` | state, model version, checkpoint name | renewal rejected without success evidence | unexpected model policy |
| `TERMINAL_STATE` | terminal state, response snapshot presence, audit phase | replay only, no mutation | business aggregate mismatch |
| `RECOVERY_STATE` | recovery status, stale snapshot presence | recovery wins over snapshot | hidden recovery in API |
| `MODEL_VERSION_MISMATCH` | stored model version and executor path | no downgrade or cross-model replay | new commands affected |
| `EXECUTION_STATUS_MISMATCH` | stored execution status and expected transition | transition rejected/fail-closed | status oscillation |
| `UNSUPPORTED_CHECKPOINT` | checkpoint name and policy row | worker stops before business mutation | repeated unsupported checkpoint |
| `FINALIZE_RECOVERY_REQUIRED` | local evidence, outbox, external confirmation status | no confirmed finality claim | evidence ambiguity |
| `RECOVERY_REQUIRED` | command, outbox, audit, business aggregate state | no committed success claim | conflicting durable state |
| `OUTBOX_CONFIRMATION_UNKNOWN` | outbox record, broker evidence, reconciliation action | resolved only with evidence | broker evidence unavailable |

## Drill 1: FINALIZE_RECOVERY_REQUIRED

- Precondition: FDP-29 command enters `FINALIZE_RECOVERY_REQUIRED`.
- Command state sample: `state=FINALIZE_RECOVERY_REQUIRED`, `execution_status=RECOVERY_REQUIRED`, `public_status=FINALIZE_RECOVERY_REQUIRED`.
- Expected metrics sample: `regulated_mutation_recovery_required_count=1`.
- Expected API/inspection output sample: `response_snapshot_present=false`, `local_commit_marker=null`, `lease_renewal_count=1`.
- Safe action: inspect command, alert aggregate, outbox, response snapshot, local success audit, and record audit trail.
- Forbidden action: submit a new idempotency key, manually edit `AlertDocument`, or mark evidence confirmed.
- Required authority: fraud ops admin or approved recovery operator.
- Audit note: `FDP-35 drill FINALIZE_RECOVERY_REQUIRED inspected; no false success exposed.`
- Pass/fail result: `[ ] PASS [ ] FAIL`.

## Drill 2: RECOVERY_REQUIRED With Stale Snapshot

- Precondition: legacy command has `responseSnapshot` and `execution_status=RECOVERY_REQUIRED`.
- Command state sample: `state=EVIDENCE_PENDING`, `execution_status=RECOVERY_REQUIRED`, `response_snapshot_present=true`.
- Expected metrics sample: `regulated_mutation_recovery_required_count=1`.
- Expected API/inspection output sample: recovery status wins over snapshot.
- Safe action: inspect durable command truth and business aggregate.
- Forbidden action: expose stale snapshot as committed success.
- Required authority: fraud ops admin.
- Audit note: `Recovery state wins over stale snapshot.`
- Pass/fail result: `[ ] PASS [ ] FAIL`.

## Drill 3: Stale Owner / Lease Takeover

- Precondition: stale worker rejected after another owner took lease.
- Command state sample: `execution_status=PROCESSING`, current `lease_owner` differs from stale worker owner.
- Expected metrics sample: `regulated_mutation_stale_write_rejected_total{reason="STALE_LEASE_OWNER"} > 0`.
- Expected API/inspection output sample: current lease expiry and renewal count visible.
- Safe action: verify no duplicate business mutation and inspect metrics.
- Forbidden action: manually rewrite lease owner or lease expiry.
- Required authority: fraud ops admin.
- Audit note: `Stale owner rejected; no duplicate mutation found.`
- Pass/fail result: `[ ] PASS [ ] FAIL`.

## Drill 4: Checkpoint Renewal Budget Exceeded

- Precondition: checkpoint renewal exceeds budget.
- Command state sample: `execution_status=RECOVERY_REQUIRED`, `degradation_reason=LEASE_RENEWAL_BUDGET_EXCEEDED`.
- Expected metrics sample: `regulated_mutation_checkpoint_no_progress_total{reason="BUDGET_EXCEEDED"} > 0`.
- Expected API/inspection output sample: no attempted audit/business/outbox/snapshot for pre-audit checkpoint.
- Safe action: inspect long-running PROCESSING dashboard and open incident if repeated.
- Forbidden action: increase budget blindly.
- Required authority: fraud ops admin plus platform owner for budget changes.
- Audit note: `Budget exceeded drill completed; no progress misclassified.`
- Pass/fail result: `[ ] PASS [ ] FAIL`.

## Drill 5: Long-running PROCESSING

- Precondition: command remains `PROCESSING` beyond threshold.
- Command state sample: `state=AUDIT_ATTEMPTED`, `execution_status=PROCESSING`, `lease_renewal_count=3`.
- Expected metrics sample: long-running PROCESSING bucket over threshold.
- Expected API/inspection output sample: state, execution status, lease expiry, renewal count, degradation reason.
- Safe action: decide whether wait or recovery is safe based on lease and business state.
- Forbidden action: create a new command to bypass the stuck one.
- Required authority: fraud ops admin.
- Audit note: `Long-running PROCESSING inspected with bounded fields.`
- Pass/fail result: `[ ] PASS [ ] FAIL`.

## Filled Example

```text
drill_id: FDP35-DRILL-2026-05-05-001
operator: fraud-ops-admin
command_lookup: idempotency_hash=96e6f95f...
state: FINALIZE_RECOVERY_REQUIRED
execution_status: RECOVERY_REQUIRED
lease_expires_at: 2026-05-05T18:10:00Z
lease_renewal_count: 2
outbox_records: 0
success_audit_id: null
response_snapshot_present: false
safe_action: escalated to recovery owner
forbidden_actions_confirmed_not_taken: true
result: PASS
```
