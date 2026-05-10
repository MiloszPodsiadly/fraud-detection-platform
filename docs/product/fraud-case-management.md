# Fraud Case Management

Status: current product-domain module.

## Purpose

Fraud Case Management connects one or more fraud alerts to an investigator workflow owned by `alert-service`.
It is a product workflow for case triage and investigation; it does not add ML behavior, external CRM integration,
document/file attachment storage, advanced RBAC, Kafka/outbox semantic changes, or production release controls.

FDP-42 is a local audited lifecycle workflow. It is not a `RegulatedMutationCoordinator` workflow, not the FDP-29
evidence-gated finalize model, not lease fenced, not replay safe, and not external finality. The legacy regulated
`PATCH /fraud-cases/{caseId}` path remains separately available for backward compatibility and is not extended by
these local lifecycle endpoints.

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

## Duplicate Submit Policy

The local lifecycle POST endpoints are intentionally non-idempotent. Repeating `notes` or `decisions` appends another
note or decision and another audit entry. Clients must not blindly retry local lifecycle POSTs after ambiguous network
failures. Repeated close or reopen is rejected by lifecycle policy when the current status no longer allows the
requested transition. Reassigning to the same investigator is treated as an audited reassignment, not a replay.
Submitting the same create request again creates an independent case unless a future idempotency contract is added.

## Audit And ACID Semantics

Every analyst lifecycle mutation writes a `FraudCaseAuditEntryDocument` in the same
`RegulatedMutationTransactionRunner` callback as the business state change. When the platform runs with Mongo transactions enabled
(`app.regulated-mutations.transaction-mode=REQUIRED`), case state and audit append commit or roll back together.
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
