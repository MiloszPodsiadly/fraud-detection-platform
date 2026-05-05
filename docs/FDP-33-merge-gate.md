# FDP-33 Merge Gate

FDP-33 is merge-safe only as bounded owner-fenced lease renewal for regulated mutation commands. It is not production enablement for FDP-29 and not an external-finality claim.

## Merge Requirements

- owner-fenced renewal implemented
- stale owner cannot renew
- expired owner cannot renew
- terminal and recovery states cannot renew
- unknown or mismatched model version fails closed
- renewal budget enforced
- max renewal count enforced
- max total lease duration enforced
- renewal cannot update business or evidence fields
- renewal cannot create infinite `PROCESSING`
- metrics use low-cardinality labels only
- operational runbook exists
- FDP-32 stale-worker and fencing tests pass
- FDP-31 claim and replay tests pass
- FDP-29 integration tests pass
- public API statuses unchanged
- Kafka and outbox semantics unchanged
- transaction boundaries unchanged
- FDP-29 feature flags unchanged

## Production And Bank Enablement Gate

Production or bank operation requires:

- transaction-mode `REQUIRED`
- lease duration budget reviewed
- renewal budget configured
- dashboards for renewal, stale rejection, takeover, remaining budget, and transition latency
- alerts for stale owner, expired lease, budget exceeded, non-renewable state, and repeated takeover
- canary or staging soak
- operator drill performed
- rollback plan

## Non-Goals

- no distributed lock
- no distributed ACID
- no external finality
- no process-kill chaos proof
- no public heartbeat endpoint
- no new mutation types
- no automatic heartbeat scheduler
