# Fraud Case Management

Status: current product-domain source of truth.

## Purpose

Fraud Case Management connects one or more fraud alerts to an investigator workflow owned by `alert-service`. It is a
local audited lifecycle workflow for case triage, assignment, notes, decisions, closure, and reopen.

It does not add ML behavior, external CRM integration, document/file attachment storage, advanced RBAC, Kafka/outbox
semantic changes, or production release controls.

FDP-43 adds local retry safety for lifecycle `POST` endpoints. It reuses shared idempotency primitives from the
regulated mutation architecture, but Fraud Case Management is not a `RegulatedMutationCoordinator` workflow, not the
FDP-29 evidence-gated finalize model, not lease fenced, not global exactly-once, and not external finality. The legacy
regulated `PATCH /fraud-cases/{caseId}` path remains separately available for backward compatibility and is not
extended by the local lifecycle endpoints.

## Product Capabilities

| Capability | Current behavior |
| --- | --- |
| Case creation | Creates a case from one or more alert ids and stores them as `linkedAlertIds`. |
| Assignment | Assigns or reassigns an investigator while the case is not closed. |
| Notes | Appends investigator notes; notes have no update/delete endpoint. |
| Decisions | Appends investigator decisions; decisions have no update/delete endpoint. |
| Status transition | Uses `FraudCaseTransitionPolicy` for allowed lifecycle movement. |
| Close | Closes a resolved case with a closure reason. |
| Reopen | Reopens a closed case with a reason. |
| Search | Filters by status, assignee, priority, risk level, creation window, or linked alert id. |
| Audit | Exposes append-only analyst lifecycle audit entries to users with `fraud-case:audit:read`. |

Manual case creation does not copy alert ids into `transactionIds`; `transactionIds` remains empty until
transaction-derived case ingestion supplies real transaction references.

## Lifecycle

| From | Allowed To |
| --- | --- |
| `OPEN` | `IN_REVIEW`, `ESCALATED` |
| `IN_REVIEW` | `ESCALATED`, `RESOLVED` |
| `ESCALATED` | `IN_REVIEW`, `RESOLVED` |
| `RESOLVED` | `CLOSED` |
| `CLOSED` | `REOPENED` |
| `REOPENED` | `IN_REVIEW`, `ESCALATED` |

Closed cases cannot be assigned, annotated, or receive decisions. The only allowed mutation for a closed case is an
explicit reopen with a reason.

`RESOLVED` is not terminal. A resolved case can still be assigned, receive notes, and receive decisions until it is
explicitly closed with a closure reason.

## API And Authority Boundary

| Surface | Required authority |
| --- | --- |
| Read/search case details | `fraud-case:read` |
| Lifecycle mutation `POST` endpoints | `fraud-case:update` |
| Audit history | `fraud-case:audit:read` |

The public lifecycle API is documented in [Fraud Case API](../api/fraud_case_api.md). Every local lifecycle `POST`
requires `X-Idempotency-Key`.

## Lifecycle Idempotency Policy

The local lifecycle `POST` endpoints require `X-Idempotency-Key`. The key is globally unique within the fraud-case
lifecycle idempotency domain: storage uses one record per key hash, while action, resolved backend actor, scope, and
request hash are conflict-checked claim fields.

Same key + same payload + same resolved backend actor/action and scope replays the stored response and does not append
another note, decision, case audit entry, or status transition. Same key with different payload, actor, action, or
scope returns `IDEMPOTENCY_KEY_CONFLICT`.

Concurrent same-key requests do not duplicate lifecycle mutation, audit entry, or idempotency record. Depending on
timing, the competing request may receive a stable replay response or an idempotency-in-progress conflict. FDP-43
guarantees side-effect idempotency for local lifecycle operations, not deterministic concurrent response timing.

This is local lifecycle retry safety. It is not distributed locking, distributed ACID, lease fencing, regulated
mutation finality, or global exactly-once execution.

Raw idempotency keys and raw request payloads are not stored or exposed; only key hashes, request hashes, bounded
action/actor/scope metadata, and safe response snapshots are stored. Idempotency records are retained for
`app.fraud-cases.idempotency.retention` (`PT24H` by default). After the retention window and eventual Mongo TTL
deletion, retrying the same key may execute as a new lifecycle operation.

Replay also requires a valid authenticated/resolved actor context. FDP-43 does not replay when actor resolution fails
before idempotency lookup. Response snapshots are bounded at runtime. If a replay snapshot would exceed the configured
limit, the lifecycle operation fails closed and rolls back the idempotency record, case mutation, and audit append.

## Audit And Transaction Semantics

Every analyst lifecycle mutation writes a `FraudCaseAuditEntryDocument` in the same local transaction callback as the
idempotency record and business state change. When Mongo transactions are enabled with
`app.regulated-mutations.transaction-mode=REQUIRED`, idempotency record, case state, and audit append commit or roll
back together.

FDP-42 analyst lifecycle atomicity requires Mongo transactions. Bank-grade case+audit atomic rollback requires
`app.regulated-mutations.transaction-mode=REQUIRED`; if transaction mode is `OFF`, FDP-42 must not claim rollback
atomicity. Integration tests prove atomicity using `MongoTransactionManager` and transaction-mode `REQUIRED`.

Audit details are intentionally small. They include identifiers and decision metadata such as assignment changes,
note ids, decision ids, status changes, and close/reopen reasons; they do not store raw request payloads, raw
idempotency keys, lease owners, payload hashes, raw exceptions, or stack traces. The audit endpoint intentionally
exposes `actorId` to users with the dedicated `fraud-case:audit:read` authority.

Append-only is enforced by application API shape and architecture tests. It is not WORM storage, legal notarization,
or bank certification.

## System Event Ingestion

`handleScoredTransaction(...)` is event-derived candidate ingestion. It can create or update system-generated fraud
case candidates from scored transaction events and transaction snapshots.

This path is separate from analyst lifecycle mutations. It is not an analyst lifecycle mutation, not analyst lifecycle
mutation behavior, not analyst lifecycle mutation evidence, not an investigator action, and not an investigator decision. It does not claim FDP-42 analyst
lifecycle audit semantics.

## Related Documents

- [Fraud Case API](../api/fraud_case_api.md)
- [Fraud case management architecture](../architecture/fraud_case_management_architecture.md)
- [Fraud case operations](../runbooks/fraud_case_operations.md)
- [Endpoint authorization map](../security/endpoint_authorization_map.md)

## Non-Claims

Fraud Case Management is not evidence-gated, not lease-fenced replay safety, not a regulated mutation finality claim,
and not external finality. It does not provide distributed ACID, distributed locking, global exactly-once execution,
WORM storage, legal notarization, bank certification, or production enablement.
