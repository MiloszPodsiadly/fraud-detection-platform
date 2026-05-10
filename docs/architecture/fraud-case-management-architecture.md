# Fraud Case Management Architecture

Status: current architecture note.

## Boundary

Fraud Case Management is implemented inside `alert-service`. It reuses existing service-local DTOs, Mongo
documents, repositories, exception handling, authentication, and RBAC. It does not introduce a new service,
message topic, outbox contract, ML path, or release-governance model.

## Flow

```text
FraudCaseController
  -> FraudCaseManagementService
  -> FraudCaseTransitionPolicy
  -> FraudCaseRepository / FraudCaseNoteRepository / FraudCaseDecisionRepository
  -> FraudCaseAuditService
  -> FraudCaseAuditRepository
```

Controllers handle HTTP and validation only. `FraudCaseManagementService` orchestrates use cases.
`FraudCaseTransitionPolicy` owns lifecycle validation. `FraudCaseAuditService` appends immutable audit entries.
`FraudCaseResponseMapper` separates persistence documents from API responses.

## State And Append-Only Records

- `FraudCaseDocument` stores case state, stable `caseNumber`, linked alerts, assignment, priority, risk level,
  timestamps, closure metadata, and optimistic version.
- `FraudCaseNoteDocument` is append-only.
- `FraudCaseDecisionDocument` is append-only.
- `FraudCaseAuditEntryDocument` is append-only and ordered by `occurredAt`.

## Transaction Direction

All mutations are wrapped in `RegulatedMutationTransactionRunner.runLocalCommit`. In local default mode this keeps
the code path aligned with the rest of the service. In transaction-required deployments, Mongo transaction manager
support provides atomic case state + audit persistence.

## Invariants

- Case creation requires at least one alert id.
- Status transitions go through `FraudCaseTransitionPolicy`.
- Closed cases cannot be modified except explicit reopen.
- Notes, decisions, and audit entries have no update/delete endpoint.
- Existing regulated mutation semantics for legacy fraud-case `PATCH` remain unchanged.
