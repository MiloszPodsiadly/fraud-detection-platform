# FDP-35 Regulated Mutation Recovery Drill

This operator drill is for regulated mutation recovery. It uses sample values only and contains no secrets.

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

