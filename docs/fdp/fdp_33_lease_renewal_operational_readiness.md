# FDP-33 Lease Renewal Operational Readiness

Status: branch evidence.


## Problem

Regulated mutation workers use owner-fenced leases to prevent stale workers from writing command transitions after takeover. Some future regulated mutation paths may run longer than the initial lease duration. Without a bounded renewal primitive, operators must choose between very long leases and premature takeover risk.

## Decision

FDP-33 adds an internal, owner-fenced, bounded lease renewal primitive for regulated mutation commands. The primitive renews only the durable lease metadata for the current owner and only while the command is still in an explicitly renewable processing state.

No public heartbeat endpoint is introduced. No scheduler or automatic infinite renewal loop is introduced. Current executors do not have to heartbeat unless a later mutation path proves a safe checkpoint is needed.

## Runtime Adoption Contract

FDP-33 introduces a bounded, owner-fenced renewal primitive and operational readiness around that primitive. FDP-33 does not add an automatic heartbeat scheduler and does not automatically call renewal from current executors.

future runtime adoption must add explicit safe checkpoints with tests. A safe checkpoint must sit before or after bounded local work, not during non-idempotent external side effects. Any future executor renewal call must use the current `RegulatedMutationClaimToken` and must stop immediately if renewal is rejected.

## Operational Scenario Names

Use stable scenario names in runbooks and dashboards so alerts are searchable. `Worker stuck but renewing` means
renewal count increases while the command state does not advance; treat it as stuck processing until proven otherwise.
`Budget exceeded flood` means renewal budget exhaustion repeats across commands and requires recovery triage before
any budget increase. `Worker renewing but not progressing` is the dashboard wording for the same no-progress pattern.
Operators must not increase budget blindly and must not bypass checkpoint renewal when clearing backlogs.
Do not increase budget blindly. Do not bypass checkpoint renewal.
do not increase budget blindly. do not bypass checkpoint renewal.
Checkpoint renewal must not be treated as business progress.
do not bypass fencing. do not increase lease budget blindly.
Do not manually rewrite lease_owner.
Do not manually extend expired leases.
Do not mark evidence confirmed manually.
Do not edit business aggregate directly.
Do not submit a new idempotency key for recovery bypass.
Do not disable fencing/renewal guards to clear backlog.

Renewal preserves ownership only; it does not prove business progress. Lease renewal is not a guarantee of progress; it only preserves ownership under bounded time.

## Renewal Caller Contract

Renewal is a private worker checkpoint API. A worker may call it only after it owns a valid claim token and only after a durable checkpoint that is safe to retry. It is not a progress signal for public callers, controllers, UI flows, analysts, operators, Kafka consumers outside the regulated mutation executor boundary, or recovery scripts.

A renewal caller must pass the original claim token and a positive requested extension. It must not derive a fresh command id, lease owner, model version, or state from request input. It must treat every rejection as authoritative and stop using the rejected token. A caller must never renew after terminal success, visible recovery, outbox publication, audit success, or failed business validation.

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

Direct budget exhaustion is durable. If the current owner is still active but the configured count or total time budget is exhausted, FDP-33 marks the command `RECOVERY_REQUIRED` with degradation reason `LEASE_RENEWAL_BUDGET_EXCEEDED`. For `EVIDENCE_GATED_FINALIZE_V1`, the state is moved to `FINALIZE_RECOVERY_REQUIRED` and public status is set to `FINALIZE_RECOVERY_REQUIRED`. For legacy commands, the existing processing state is preserved and only execution status and recovery metadata are changed. A same-owner concurrent renewal loser whose peer already advanced the lease does not rewrite the command into recovery just because the peer consumed the final slot.

## Renewable States

Legacy regulated mutation renewal is limited to active processing states:

- `REQUESTED`
- `AUDIT_ATTEMPTED`
- `BUSINESS_COMMITTING`
- `BUSINESS_COMMITTED`
- `SUCCESS_AUDIT_PENDING`

`REQUESTED` is renewable for legacy compatibility because the legacy executor may hold the claim before the first durable phase transition. `BUSINESS_COMMITTED` is renewable because the business aggregate has already been written but success audit/outbox follow-up may still need a bounded worker lease. Neither state permits renewal after terminal command status, public finality, or recovery status.

## Legacy Renewable State Justification

Legacy renewal does not imply business success and must not be used as idle queue parking. For legacy model budget exhaustion, renewal preserves state for compatibility but `execution_status=RECOVERY_REQUIRED` is authoritative. Recovery status wins over `responseSnapshot`; execution_status and recovery precedence remain authoritative.

| State | Why renewable | Risk | Guardrail | Test evidence |
| --- | --- | --- | --- | --- |
| `REQUESTED` | Renewable only after claim while worker prepares first durable phase; budget-bounded; renewal mutates lease metadata only. | Idle queue parking if a worker renews without progress. | No automatic scheduler; safe checkpoint review required before runtime adoption. | `RegulatedMutationLeaseRenewalPolicyTest`, `RegulatedMutationLeaseRenewalIntegrationTest` |
| `AUDIT_ATTEMPTED` | Worker may be between attempted audit and business work; budget-bounded. | Attempted audit can be mistaken for business success. | Renewal never changes response snapshots, outbox ids, audit ids, state, or public status. | `RegulatedMutationLeaseRenewalPolicyTest`, `RegulatedMutationArchitectureTest` |
| `BUSINESS_COMMITTING` | Critical local business transaction window; renewal reduces false stale recovery. | Business write may be ambiguous if the process stalls. | Budget exhaustion and expired unsafe replay move to recovery-required handling. | `RegulatedMutationClaimReplayPolicyCompatibilityTest` |
| `BUSINESS_COMMITTED` | Compatibility window for legacy post-business success audit/outbox follow-up; execution_status and recovery precedence remain authoritative. | Snapshot replay could hide recovery if status precedence regresses. | `RECOVERY_REQUIRED` wins over response snapshot and returns recovery response. | `RegulatedMutationClaimReplayPolicyCompatibilityTest` |
| `SUCCESS_AUDIT_PENDING` | Worker may retry success audit without rerunning business mutation. | Renewal could hide success-audit contention. | Bounded renewal count and budget-exceeded recovery. | `RegulatedMutationLeaseRenewalIntegrationTest`, `RegulatedMutationClaimReplayPolicyCompatibilityTest` |

Evidence-gated finalize renewal is limited to active FDP-29 worker states:

- `EVIDENCE_PREPARING`
- `EVIDENCE_PREPARED`
- `FINALIZING`

Terminal, rejected, finalized, committed, failed, and recovery states are not renewable. Unknown or unsupported states fail closed.

## Failure Modes

- invalid extension: rejected before any Mongo update
- command not found: rejected before any Mongo update
- stale owner: rejected and treated as stale lease
- expired lease: rejected and treated as expired lease
- non-renewable state: rejected
- terminal state: rejected
- recovery state: rejected
- model version mismatch: rejected
- execution status mismatch: rejected
- budget exceeded: rejected with budget-exceeded failure and durable recovery marking for direct budget exhaustion
- unknown reason: normalized to `UNKNOWN` for metrics

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

Use `docs/runbooks/regulated_mutation_recovery.md` for per-reason operator handling. The runbook covers `STALE_OWNER`, `EXPIRED_LEASE`, `BUDGET_EXCEEDED`, `INVALID_EXTENSION`, `COMMAND_NOT_FOUND`, `NON_RENEWABLE_STATE`, `TERMINAL_STATE`, `RECOVERY_STATE`, `MODEL_VERSION_MISMATCH`, `EXECUTION_STATUS_MISMATCH`, and `UNKNOWN`.

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
