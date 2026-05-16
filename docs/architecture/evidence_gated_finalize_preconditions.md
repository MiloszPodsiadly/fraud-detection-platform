# Evidence-Gated Finalize Preconditions

This document defines the local evidence preconditions that must be satisfied before the feature-flagged submit-decision path may enter `FINALIZING`.

Do not require Kafka publish before finalize. Kafka publication is a downstream outbox effect after the local finalize transaction.

The current implementation gate version is `LOCAL_EVIDENCE_GATE_V1`. Runtime precondition results use explicit status values:

- `SATISFIED`
- `REJECTED_EVIDENCE_UNAVAILABLE`
- `FAILED_BUSINESS_VALIDATION`
- `FINALIZE_RECOVERY_REQUIRED`

Each result carries a stable reason code plus bounded checked/skipped precondition names. Operators must interpret those fields as local-gate evidence only.

| Precondition | Bank Mode | Non-Bank Mode | Source of Truth | Failure Behavior | Retry Behavior | Boundary |
| --- | --- | --- | --- | --- | --- | --- |
| Durable regulated command exists | Required | Required | `regulated_mutation_commands` | No mutation; return pending/rejected status if persistence fails | Safe retry may create or read same command by idempotency key | Local ACID |
| Idempotency key accepted and conflict-free | Required | Required | Command idempotency hash and canonical intent hash | Conflict returns conflict status; no mutation | Same key/same payload replays state; different payload/actor rejected | Local ACID |
| `ATTEMPTED` audit phase recorded | Required | Required for regulated writes | Durable audit store | `REJECTED_EVIDENCE_UNAVAILABLE`; no visible business mutation | Retry may attempt evidence preparation again if command proves no finalize happened | Local durable evidence |
| `ATTEMPTED` external anchoring status known if required | Target design only | Optional or best-effort by mode | External publication status index plus local audit chain | Reject or degrade before finalize according to policy; never guess success | Retry evidence preparation; no hidden background success assumption | External/eventual |
| Audit phase deterministic key reserved | Required | Required | Deterministic audit request id, for example `commandId:ATTEMPTED|SUCCESS` | Reject before visible mutation if key cannot be reserved | Same deterministic key may be retried idempotently | Local proof |
| Transaction capability healthy | Required | Optional by mode, but explicit | Mongo transaction capability probe | Startup or command rejection in bank mode | Retry only after capability restored | Local runtime capability |
| Transactional outbox can be written | Required for mutation that emits events | Required for event-emitting mutation | `transactional_outbox_records` | Reject before visible mutation if outbox write cannot participate in local transaction | Retry command if state proves no finalize happened | Local ACID |
| Trust Authority signing available if required | Target design only | Optional by mode | Trust Authority client and local verification status | Reject before visible mutation when required signing is unavailable | Retry evidence preparation; do not log token/key material | Remote/external unless local signer |
| External anchor sink available if required | Target design only | Optional/degraded by mode | External anchor client/status repository | Reject before visible mutation if required; otherwise mark pending external | Retry external publication separately | External/eventual |
| Business validation passed | Required | Required | Domain validator and current aggregate state | `FAILED_BUSINESS_VALIDATION`; no visible mutation | Same key/same payload replays validation failure unless business state changed by separate command | Local domain logic |
| Actor authorization resolved and stable | Required | Required | Backend authentication/authorization context | Reject before command finalization; no frontend-derived authority | Same key/different actor rejected | Security boundary |
| Canonical intent hash stored | Required | Required | Command document | Reject or recovery-required if intent cannot be persisted | Same payload must produce same hash; different hash conflicts | Local ACID |
| Recovery strategy registered | Required | Required for supported regulated command | Recovery strategy registry | Reject before accepting unsupported regulated command | Retry after deployment/config fix only if command has not finalized | Runtime configuration |
| Sensitive read/write audit policy available if required | Required in bank/prod policy | Optional according to mode | Audit policy configuration | Reject/fail closed before sensitive result or regulated visible mutation | Retry after policy/audit availability restored | Local policy plus durable audit |

## Finalize Gate Rule

`EVIDENCE_PREPARED` means every required local precondition has a durable local proof. It does not mean external
witness confirmation, Kafka broker delivery, or legal proof has completed.

`FINALIZING` may start only from `EVIDENCE_PREPARED`.

New evidence-gated submit-decision commands persist `FINALIZED_EVIDENCE_PENDING_EXTERNAL` inside the local Mongo
transaction that applies the business aggregate mutation, writes the transactional outbox record, stores the response
snapshot, writes local success audit evidence through `RegulatedMutationLocalAuditPhaseWriter`, and stores the local
finalize marker.

`FINALIZED_VISIBLE` is retained only as a compatibility/repair state for previously persisted or interrupted commands.

## Checked Preconditions

`LOCAL_EVIDENCE_GATE_V1` currently checks:

- command model version is `EVIDENCE_GATED_FINALIZE_V1`
- transaction mode is `REQUIRED`
- durable `ATTEMPTED` audit is already recorded
- transactional outbox repository is available
- outbox recovery is enabled
- submit-decision recovery strategy is registered
- deterministic success audit phase key can be derived
- actor, resource, and action match the stored canonical intent
- target alert exists and is not already decided
- requested decision is present in the canonical intent

This is intentionally narrower than the target precondition table. External anchor readiness and Trust Authority
signing readiness remain post-finalize asynchronous confirmation signals in the current evidence-gated finalize model.

The local success audit writer does not call `AuditService`, `AuditEventPublisher`,
`ExternalAuditAnchorPublisher`, or Kafka publishers. External audit publication remains post-commit/asynchronous
evidence confirmation.

`EvidenceGatedFinalizeExecutor` owns evidence preparation, local precondition evaluation, and the local finalize
transaction. The shared Mongo coordinator loads or creates commands and routes evidence-gated commands to that
executor by `mutation_model_version`; it does not implement the evidence-gated transaction body directly.

## Local SUCCESS Audit Writer

`RegulatedMutationLocalAuditPhaseWriter` is used only by the evidence-gated submit-decision finalize transaction.
It writes the local `SUCCESS` phase to the authoritative `audit_events` collection with deterministic request id
`commandId:SUCCESS`, appends the same local audit-chain record model, and writes the matching local anchor record.

It is intentionally not a generic audit service and is not a second source of truth. It does not call `AuditService`,
`AuditEventPublisher`, Kafka, external anchor publishers, or Trust Authority clients. Broader use outside the
evidence-gated finalize coordinator path requires architecture review.

The local `SUCCESS` phase proves only local durable evidence inside Mongo. It does not provide external finality,
legal notarization, WORM storage, distributed ACID, or independent witness proof.

The writer retry policy is bounded by:

- `app.audit.local-phase-writer.max-append-attempts`
- `app.audit.local-phase-writer.backoff-ms`
- `app.audit.local-phase-writer.max-total-wait-ms`
- `app.audit.local-phase-writer.allow-long-total-wait`

Evidence-gated finalize startup fails closed when the writer, retry policy, chain index initializer, or required
unique chain indexes are missing or unsafe. This prevents enabling a local finalize path that can silently truncate,
fork, or indefinitely wait on the local audit chain.

Writer health is exposed through low-cardinality metrics:

- `fdp29_local_audit_chain_append_total{outcome}`
- `fdp29_local_audit_chain_retry_total{reason}`
- `fdp29_local_audit_chain_append_duration_ms`
- `fdp29_local_audit_chain_lock_release_failure_total`

These metrics are operational signals only. They are not compliance evidence and must not use dynamic labels such as
actors, resources, command ids, audit ids, lock owners, paths, or exception messages.

`confirmPendingEvidence(limit)` accepts batch limits from `1` to `100`. A limit of `0` or less is a no-op and returns `0`; it does not process one command implicitly.
