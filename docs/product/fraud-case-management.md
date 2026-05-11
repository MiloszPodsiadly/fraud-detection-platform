# Fraud Case Management

Status: current product-domain module.

## Purpose

Fraud Case Management connects one or more fraud alerts to an investigator workflow owned by `alert-service`.
It is a product workflow for case triage and investigation; it does not add ML behavior, external CRM integration,
document/file attachment storage, advanced RBAC, Kafka/outbox semantic changes, or production release controls.

FDP-43 keeps Fraud Case Management as a local audited lifecycle workflow and adds local retry safety for lifecycle
POSTs. It reuses shared idempotency primitives from the existing regulated mutation architecture, but it is not a
`RegulatedMutationCoordinator` workflow, not the FDP-29 evidence-gated finalize model, not lease fenced, not global
exactly-once, and not external finality. The legacy regulated `PATCH /fraud-cases/{caseId}` path remains separately
available for backward compatibility and is not extended by these local lifecycle endpoints.

## Lifecycle

| From | Allowed To |
| --- | --- |
| `OPEN` | `IN_REVIEW`, `ESCALATED` |
| `IN_REVIEW` | `ESCALATED`, `RESOLVED` |
| `ESCALATED` | `IN_REVIEW`, `RESOLVED` |
| `RESOLVED` | `CLOSED` |
| `CLOSED` | `REOPENED` |
| `REOPENED` | `IN_REVIEW`, `ESCALATED` |

Closed cases cannot be assigned, annotated, or receive decisions. The only allowed mutation for a closed case is
explicit reopen with a reason.

`RESOLVED` is not terminal. A resolved case can still be assigned, receive notes, and receive decisions until it is
explicitly closed with a closure reason.

## Product Operations

- Create a case from one or more alert ids.
- Assign or reassign an investigator.
- Append investigator notes.
- Append investigator decisions.
- Transition status through `FraudCaseTransitionPolicy`.
- Close a resolved case with a closure reason.
- Reopen a closed case with a reason.
- Search by status, assignee, priority, risk level, creation window, or linked alert id.
- Read the append-only case audit trail.

Manual case creation stores request `alertIds` as `linkedAlertIds`. It does not copy those alert ids into
`transactionIds`; `transactionIds` remains empty until transaction-derived case ingestion supplies real transaction
references.

## System-Generated Case Candidate Ingestion

`handleScoredTransaction(...)` is event-derived candidate ingestion. It can create or update system-generated fraud
case candidates from scored transaction events and transaction snapshots. This path is separate from analyst
lifecycle mutations, is not analyst lifecycle mutation behavior, does not represent investigator action or decision,
and does not claim FDP-42 analyst lifecycle audit semantics.

## Lifecycle Idempotency Policy

The local lifecycle POST endpoints require `X-Idempotency-Key`. The key is globally unique within the fraud-case
lifecycle idempotency domain: storage uses one record per key hash, while action, resolved backend actor, scope, and
request hash are conflict-checked claim fields. Same key + same payload + same resolved backend actor/action and scope
returns the stored response snapshot. Replay does not append another note, decision, case audit entry, or status
transition. Same key with different payload, actor, action, or scope returns a local conflict.

Concurrent same-key requests do not duplicate lifecycle mutation, audit entry, or idempotency record.
Depending on timing, the competing request may receive a stable replay response or an idempotency-in-progress conflict.
FDP-43 guarantees side-effect idempotency for local lifecycle operations, not deterministic concurrent response timing.
This is local lifecycle retry safety; it is not distributed locking, distributed ACID, lease fencing, regulated
mutation finality, or global exactly-once execution.

The idempotency record, lifecycle mutation, and fraud-case audit append commit or roll back together when Mongo
transactions are enabled with transaction-mode `REQUIRED`. Raw idempotency keys and raw request payloads are not
stored or exposed; only key hashes, request hashes, bounded action/actor/scope metadata, and safe response snapshots
are stored.

Idempotency records are retained for `app.fraud-cases.idempotency.retention` (`PT24H` by default). After the retention
window and eventual Mongo TTL deletion, retrying the same key may execute as a new lifecycle operation. Replay also
requires a valid authenticated/resolved actor context; FDP-43 does not replay when actor resolution fails before
idempotency lookup.

Response snapshots are bounded at runtime. If a replay snapshot would exceed the configured limit, the lifecycle
operation fails closed and rolls back the idempotency record, case mutation, and audit append.

## Audit And ACID Semantics

Every analyst lifecycle mutation writes a `FraudCaseAuditEntryDocument` in the same local transaction callback as the
idempotency record and business state change. When the platform runs with Mongo transactions enabled
(`app.regulated-mutations.transaction-mode=REQUIRED`), idempotency record, case state, and audit append commit or roll back together.
FDP-42 analyst lifecycle atomicity requires Mongo transactions. Bank-grade case+audit atomic rollback requires
`app.regulated-mutations.transaction-mode=REQUIRED`; if transaction mode is `OFF`, FDP-42 must not claim rollback
atomicity. Integration tests prove atomicity using `MongoTransactionManager` and transaction-mode `REQUIRED`.

Audit details are intentionally small. They include identifiers and decision metadata such as assignment changes,
note ids, decision ids, status changes, and close/reopen reasons; they do not store raw request payloads, raw
idempotency keys, lease owners, payload hashes, or stack traces. The audit endpoint intentionally exposes `actorId`
to users with the dedicated `fraud-case:audit:read` authority.

Append-only is enforced by application API shape and architecture tests. It is not WORM storage, legal notarization,
or bank certification.

## Operational Notes

The module reuses `alert-service` authentication and authority boundaries. Read endpoints require
`fraud-case:read`; mutation endpoints require `fraud-case:update`; audit history requires
`fraud-case:audit:read`. Existing regulated fraud-case `PATCH` behavior remains in place for backward compatibility.
