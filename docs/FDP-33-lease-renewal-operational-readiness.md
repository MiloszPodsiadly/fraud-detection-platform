# FDP-33 Lease Renewal Operational Readiness

## Problem

Regulated mutation workers use owner-fenced leases to prevent stale workers from writing command transitions after takeover. Some future regulated mutation paths may run longer than the initial lease duration. Without a bounded renewal primitive, operators must choose between very long leases and premature takeover risk.

## Decision

FDP-33 adds an internal, owner-fenced, bounded lease renewal primitive for regulated mutation commands. The primitive renews only the durable lease metadata for the current owner and only while the command is still in an explicitly renewable processing state.

No public heartbeat endpoint is introduced. No scheduler or automatic infinite renewal loop is introduced. Current executors do not have to heartbeat unless a later mutation path proves a safe checkpoint is needed.

## Lease Renewal Invariant

A renewal may extend `lease_expires_at` only when all of these are true:

- command id matches the claim token
- `lease_owner` matches the claim token
- `lease_expires_at > now`
- `execution_status == PROCESSING`
- `mutation_model_version` matches the claim token, with missing model version treated as legacy
- durable command state is renewable for the model version
- renewal budget is not exhausted

Renewal must not change business state, public status, response snapshots, outbox ids, audit ids, local commit markers, idempotency data, request hashes, actor, resource, action, or resource type.

## Renewal Policy And Budget

The renewal policy is configured under `app.regulated-mutations.lease-renewal.*`.

- `max-single-extension` caps one requested extension
- `max-total-lease-duration` caps total time from the claim budget start
- `max-renewal-count` caps the number of successful renewals

Requested extensions above `max-single-extension` are capped. Extensions beyond `max-total-lease-duration` are capped to the budget end. If the computed new expiry would not move the lease forward, renewal fails closed as budget exceeded.

Missing renewal metadata is backward compatible. Missing `lease_renewal_count` means `0`. Missing `lease_budget_started_at` falls back to the claim token `claimedAt`, then command `createdAt`, then current time.

## Renewable States

Legacy regulated mutation renewal is limited to active processing states:

- `REQUESTED`
- `AUDIT_ATTEMPTED`
- `BUSINESS_COMMITTING`
- `BUSINESS_COMMITTED`
- `SUCCESS_AUDIT_PENDING`

Evidence-gated finalize renewal is limited to active FDP-29 worker states:

- `EVIDENCE_PREPARING`
- `EVIDENCE_PREPARED`
- `FINALIZING`

Terminal, rejected, finalized, committed, failed, and recovery states are not renewable. Unknown or unsupported states fail closed.

## Failure Modes

- stale owner: rejected and treated as stale lease
- expired lease: rejected and treated as expired lease
- non-renewable state: rejected
- terminal state: rejected
- recovery state: rejected
- model version mismatch: rejected
- execution status mismatch: rejected
- budget exceeded: rejected with budget-exceeded failure

## Metrics

FDP-33 emits low-cardinality metrics only:

- `regulated_mutation_lease_renewal_total{model_version,state,outcome,reason}`
- `regulated_mutation_lease_renewal_budget_remaining_seconds{model_version,state}`
- `regulated_mutation_lease_renewal_extension_seconds{model_version,state,outcome}`
- `regulated_mutation_lease_renewal_rejected_total{model_version,state,reason}`
- `regulated_mutation_lease_renewal_budget_exceeded_total{model_version,state}`
- `regulated_mutation_lease_renewal_single_extension_capped_total{model_version,state}`
- `regulated_mutation_lease_renewal_total_budget_capped_total{model_version,state}`

Metric labels must not include command ids, alert ids, actor ids, lease owners, idempotency keys, request hashes, resource ids, exception messages, tokens, or paths.

## Dashboards

Recommended dashboards:

- renewal success and rejection rate by model version and state
- budget exceeded count
- extension seconds p50/p95/p99
- remaining budget seconds p50/p95/p99
- capped renewal counts
- stale owner and expired lease renewal rejections
- active `PROCESSING` command age and takeover rate from FDP-32 metrics

## Alerts

Recommended alerts:

- any sustained `BUDGET_EXCEEDED` renewal failures
- stale owner renewal rejection spike
- expired lease renewal rejection spike
- non-renewable state renewal rejection spike
- repeated lease takeover for the same operational window
- long-running `PROCESSING` commands approaching renewal budget exhaustion

## Runbooks

### STALE_OWNER Renewal Rejection

Confirm whether another worker took over after lease expiry. Inspect the regulated command by command id or idempotency hash. The old worker must stop using its claim token. Do not manually rewrite `lease_owner`.

### EXPIRED_LEASE Renewal Rejection

Confirm worker latency and lease duration budget. If the command remains claimable, allow normal takeover or recovery. Do not extend expired leases manually.

### BUDGET_EXCEEDED

Inspect mutation duration and retry behavior. A budget-exceeded renewal means FDP-33 prevented infinite `PROCESSING`. Move the command through normal recovery if the worker cannot finish inside the configured budget.

### NON_RENEWABLE_STATE

Confirm the command state. Renewal is valid only in explicitly active states. Terminal, recovery, finalized, and committed states should move through replay or recovery behavior, not heartbeat.

### Repeated Lease Takeover

Check worker health, GC pauses, Mongo latency, and configured lease duration. Repeated takeover is an operational signal, not a reason to disable fencing.

### Long-Running PROCESSING Command

Check remaining renewal budget, current state, and worker logs. If the command is stuck near budget exhaustion, prepare recovery handling instead of increasing the budget without review.

### FINALIZE_RECOVERY_REQUIRED After Failed Renewal

Treat recovery-required state as authoritative. Renewal does not override recovery. Use the regulated mutation recovery runbook and do not attempt to heartbeat a recovery command.

## Production And Bank Enablement Gate

Production or bank use requires:

- transaction-mode `REQUIRED`
- lease duration budget reviewed
- renewal budget configured
- dashboards created
- alerts created
- canary or staging soak
- operator drill for stale owner, expired lease, budget exceeded, and recovery-required states
- rollback plan

FDP-33 does not enable FDP-29 production mode by itself.

## Non-Goals

- no distributed lock
- no transaction boundary change
- no public heartbeat endpoint
- no automatic infinite renewal loop
- no Kafka or outbox semantic change
- no new mutation type
- no FDP-29 production enablement

## Known Limitations

FDP-33 is not process-kill chaos proof. It does not provide external finality or distributed ACID. Process-kill chaos testing, heartbeat scheduling, and automatic checkpoint placement remain future scope.
