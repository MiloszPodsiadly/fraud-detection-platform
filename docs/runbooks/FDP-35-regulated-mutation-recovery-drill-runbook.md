# FDP-35 Regulated Mutation Recovery Drill Runbook

Use this runbook for regulated mutation recovery drills. Operators must inspect durable command truth before taking action. Do not submit a new idempotency key, manually edit `AlertDocument`, rewrite leases, or mark evidence confirmed without proof.

## Drill Checklist

- Identify command by approved operational lookup.
- Inspect state, execution status, lease expiry, lease renewal count, degradation reason, response snapshot presence, outbox record, and audit phases.
- Confirm recovery state wins over any stale response snapshot.
- Confirm no duplicate business mutation occurred.
- Record audit note with command state and action taken.
- Escalate if durable proof is ambiguous.

## Pass Criteria

- No false committed success is exposed.
- Recovery-required commands remain operator-visible.
- Outbox and audit evidence are inspected before recovery.
- Rollback or recovery action is reversible and documented.

## Fail Criteria

- Operator uses a new idempotency key to bypass recovery.
- Operator manually edits business documents.
- Operator rewrites lease owner or lease expiry.
- Operator marks external evidence confirmed without external proof.

