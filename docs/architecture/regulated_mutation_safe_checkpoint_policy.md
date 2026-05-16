# Regulated Mutation Safe Checkpoint Policy

## Purpose

Safe checkpoint policy applies the bounded lease-renewal primitive at explicit regulated mutation executor
checkpoints. The goal is to reduce false stale-worker takeover during bounded local work while preserving the
owner-fenced transition model.

Renewal preserves ownership, not progress. Checkpoint renewal preserves bounded lease ownership. It does not prove business progress.
A successful checkpoint renewal only proves that the current worker still owns the command lease
within the configured renewal budget. It does not prove that business mutation, evidence preparation, audit, outbox
publication, or external finality completed.

Operational dashboards should flag `Worker renewing but not progressing` when renewal count increases without a
state transition.

Checkpoint renewal must not be treated as business progress.

## Invariant

Checkpoint renewal is allowed only when all of these are true:

- the worker holds a current `RegulatedMutationClaimToken`
- the command is still `PROCESSING`
- the durable command model version matches the token, with missing model version treated as legacy compatibility
- the command state and checkpoint pair is explicitly approved
- the command is not terminal, rejected, failed, recovery-required, finalized, or externally pending finality
- the renewal budget allows the requested bounded extension

Checkpoint renewal failure stops execution. After a failed checkpoint renewal, the executor must not continue to
business mutation, evidence mutation, local finalize transaction, outbox write, success audit write, response snapshot
write, or command transition.

## Safe Checkpoint Definition

A safe checkpoint is a named, reviewed executor location before or after bounded local work where lease ownership may be renewed without treating renewal as business progress.

A safe checkpoint must not be:

- a generic heartbeat
- a scheduler
- an automatic infinite renewal loop
- a public endpoint
- a progress signal
- a retry around non-idempotent external side effects
- inside non-idempotent external side-effect execution
- a transaction-boundary change

No generic heartbeat system is introduced. No automatic infinite renewal loop is introduced.

## Checkpoint Before ATTEMPTED Audit

`LEGACY_REGULATED_MUTATION` allows exactly one explicit `REQUESTED` + `PROCESSING` checkpoint: `BEFORE_ATTEMPTED_AUDIT`.

This checkpoint is ownership-only. It is allowed only because the worker has already claimed the command and the
durable command has `execution_status=PROCESSING`. It does not record `ATTEMPTED` audit, imply attempted-audit
completion, imply business progress, create a response snapshot, write an outbox event id, record success audit, or
create any local commit marker.

`BEFORE_ATTEMPTED_AUDIT` must remain a single explicit checkpoint before the attempted audit write. It must not become
a loop, idle parking mechanism, scheduler, public heartbeat, or generic pre-work renewal pattern. If this checkpoint
fails, execution stops before attempted audit and before any business mutation.

## Approved Checkpoint Policy

| Model version | Durable state | Execution status | Approved checkpoints |
| --- | --- | --- | --- |
| `LEGACY_REGULATED_MUTATION` | `REQUESTED` | `PROCESSING` | `BEFORE_ATTEMPTED_AUDIT` |
| `LEGACY_REGULATED_MUTATION` | `AUDIT_ATTEMPTED` | `PROCESSING` | `BEFORE_LEGACY_BUSINESS_COMMIT` |
| `LEGACY_REGULATED_MUTATION` | `BUSINESS_COMMITTING` | `PROCESSING` | `BEFORE_LEGACY_BUSINESS_COMMIT` |
| `LEGACY_REGULATED_MUTATION` | `BUSINESS_COMMITTED` | `PROCESSING` | `BEFORE_SUCCESS_AUDIT_RETRY` |
| `LEGACY_REGULATED_MUTATION` | `SUCCESS_AUDIT_PENDING` | `PROCESSING` | `BEFORE_SUCCESS_AUDIT_RETRY` |
| `EVIDENCE_GATED_FINALIZE_V1` | `EVIDENCE_PREPARING` | `PROCESSING` | `BEFORE_EVIDENCE_PREPARATION` |
| `EVIDENCE_GATED_FINALIZE_V1` | `EVIDENCE_PREPARED` | `PROCESSING` | `AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE`, `BEFORE_EVIDENCE_GATED_FINALIZE` |
| `EVIDENCE_GATED_FINALIZE_V1` | `FINALIZING` | `PROCESSING` | `BEFORE_EVIDENCE_GATED_FINALIZE` |

All other model/state/checkpoint combinations fail closed.

Checkpoint renewal is rejected for `RECOVERY_REQUIRED`, `FINALIZE_RECOVERY_REQUIRED`,
`REJECTED_EVIDENCE_UNAVAILABLE`, `FAILED_BUSINESS_VALIDATION`, `FINALIZED_VISIBLE`,
`FINALIZED_EVIDENCE_PENDING_EXTERNAL`, `FINALIZED_EVIDENCE_CONFIRMED`, terminal states, recovery states, rejected
states, and non-`PROCESSING` execution status.

## Failure Semantics

Checkpoint renewal rejections are authoritative. The worker must stop with the rejected token and allow normal claim, replay, or recovery behavior to decide the next action.

Required failure behavior:

- stale owner: stop immediately; the newer owner or normal recovery continues
- expired lease: stop immediately; command is eligible for takeover or recovery
- budget exceeded: stop immediately; durable budget-exceeded recovery behavior remains authoritative
- non-renewable state: stop immediately; do not infer progress from renewal
- terminal or recovery state: stop immediately; terminal/recovery state wins
- model or execution status mismatch: stop immediately; treat as deployment/state skew
- unsupported checkpoint: stop immediately; caller is outside the reviewed policy table

After failed checkpoint renewal the executor must not continue to business or evidence mutation, local finalize
transaction, outbox write, success audit write, response snapshot write, or post-checkpoint command transition.
Checkpoint renewal failure is a lease/checkpoint failure, not audit evidence failure, and must not be mapped to
post-commit audit degradation.

A successful renewal does not mean `ATTEMPTED` audit completed, business mutation completed, outbox written, success
audit recorded, evidence prepared, local finalize completed, external confirmation completed, Kafka delivered, or
legal/auditor finality reached.

## ACID Boundary

Safe checkpoint renewal does not expand transaction scope. Checkpoint renewal updates lease metadata through the
owner-fenced lease-renewal primitive and must not run as a substitute for local business transaction safety.

Transaction-mode `REQUIRED` is still required for bank-grade stale-worker business-write safety. Transaction-mode
`OFF` remains compatibility/local behavior and is not a bank-grade guarantee.

Production executors require the Spring-managed enabled `RegulatedMutationCheckpointRenewalService`. `disabled()` is
only for compatibility and unit-test constructors and is not production behavior.

## SOLID Boundary

Checkpoint policy decides whether a checkpoint is legal. Checkpoint renewal service applies that policy and delegates
renewal to the lease-renewal primitive. Executors may call only named checkpoints and must not directly implement
renewal logic.

The checkpoint renewal boundary must not depend on business repositories, audit publishers, outbox publishers, Kafka
producers, external anchors, trust authority clients, controllers, or UI/public API contracts.

## Observability

Safe checkpoint renewal emits low-cardinality checkpoint metrics only:

- `regulated_mutation_checkpoint_renewal_total{model_version,checkpoint,outcome,reason}`
- `regulated_mutation_checkpoint_renewal_blocked_total{model_version,checkpoint,reason}`
- `regulated_mutation_checkpoint_duration_seconds{model_version,checkpoint}`
- `regulated_mutation_checkpoint_no_progress_total{model_version,checkpoint,reason}`

Metric labels must not include command ids, alert ids, actor ids, lease owners, idempotency keys, request hashes, resource ids, exception messages, URLs, paths, or tokens.

`regulated_mutation_checkpoint_no_progress_total` means blocked, failed, or explicit no-progress classification. It is not emitted for a normal successful checkpoint renewal.

## Validation Gate

Merge requires explicit checkpoint policy tests, fail-closed service tests, executor failure-behavior tests, real Mongo
executor-path tests for legacy and evidence-gated checkpoints, success/stale/expired/budget paths, no mutation after
failed checkpoint, no audit degradation misclassification, low-cardinality metric tests, architecture guards,
stale-worker regression, renewal regression, and evidence-gated finalize integration regression.

## Production Gate

Safe checkpoint adoption is not production or bank enablement by itself. Production or bank operation requires
transaction-mode `REQUIRED` for bank-grade stale-worker business-write safety, positive checkpoint-renewal extension
within the configured single-extension and total-duration budgets, lease duration budget review, renewal budget
review, dashboard for checkpoint failure/no-progress, dashboard for long-running `PROCESSING` despite renewals, alert
on `BUDGET_EXCEEDED`, alert on `STALE_OWNER` and `EXPIRED_LEASE` spikes, operator drill, canary or staging soak,
rollback plan to disable checkpoint adoption or renewal config safely, and separate operational approval.

## Non-Goals

- no public heartbeat endpoint
- no scheduler
- no automatic infinite renewal loop
- no new mutation type
- no public API status changes
- no Kafka or outbox semantic changes
- no external finality
- no distributed lock
- no distributed ACID
- no process-kill chaos proof
- no evidence-gated finalize production enablement
