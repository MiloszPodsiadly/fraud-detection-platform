# Fraud Case Management

Status: current product-domain module.

## Purpose

Fraud Case Management connects one or more fraud alerts to an investigator workflow owned by `alert-service`.
It is a product workflow for case triage and investigation; it does not add ML behavior, external CRM integration,
document/file attachment storage, advanced RBAC, Kafka/outbox semantic changes, or production release controls.

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

## Audit And ACID Semantics

Every case mutation writes a `FraudCaseAuditEntryDocument` in the same `RegulatedMutationTransactionRunner`
callback as the business state change. When the platform runs with Mongo transactions enabled
(`app.regulated-mutations.transaction-mode=REQUIRED`), case state and audit append commit or roll back together.

Audit details are intentionally small. They include identifiers and decision metadata such as assignment changes,
note ids, decision ids, status changes, and close/reopen reasons; they do not store raw request payloads or stack
traces.

## Operational Notes

The module reuses `alert-service` authentication and authority boundaries. Read endpoints require
`fraud-case:read`; mutation endpoints require `fraud-case:update`. Existing regulated fraud-case `PATCH` behavior
remains in place for backward compatibility.
