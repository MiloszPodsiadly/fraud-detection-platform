# Fraud Case Management Architecture

Status: current architecture note.

## Boundary

Fraud Case Management is implemented inside `alert-service`. It reuses existing service-local DTOs, Mongo
documents, repositories, exception handling, authentication, and RBAC. It does not introduce a new service,
message topic, outbox contract, ML path, or release-governance model.

The FDP-42 local lifecycle endpoints are not implemented through `RegulatedMutationCoordinator`, are not FDP-29
evidence-gated finalize, are not lease fenced, are not replay safe, and are not external finality. The older regulated
`PATCH` path remains isolated as compatibility behavior.

## Flow

```text
FraudCaseController
  -> FraudCaseManagementService
  -> FraudCaseLifecycleService / FraudCaseQueryService
  -> FraudCaseTransitionPolicy
  -> FraudCaseRepository / FraudCaseNoteRepository / FraudCaseDecisionRepository
  -> FraudCaseSearchRepository / MongoFraudCaseSearchRepository
  -> FraudCaseAuditService
  -> FraudCaseAuditRepository
```

Controllers handle HTTP and validation only. `FraudCaseManagementService` is the compatibility facade. It delegates
analyst lifecycle work to `FraudCaseLifecycleService`, read/search/audit work to `FraudCaseQueryService`, and keeps
system event ingestion plus the legacy regulated `PATCH` path separate. `FraudCaseTransitionPolicy` owns lifecycle validation.
`FraudCaseSearchRepository` owns repository-level Mongo search instead of in-memory `findAll().stream` filtering.
`FraudCaseAuditService` appends immutable audit entries. `FraudCaseResponseMapper` separates persistence documents
from API responses.

## State And Append-Only Records

- `FraudCaseDocument` stores case state, stable `caseNumber`, linked alerts, assignment, priority, risk level,
  timestamps, closure metadata, and optimistic version.
- `FraudCaseNoteDocument` is append-only.
- `FraudCaseDecisionDocument` is append-only.
- `FraudCaseAuditEntryDocument` is append-only and ordered by `occurredAt`.

## Transaction Direction

All analyst lifecycle mutations are wrapped in `RegulatedMutationTransactionRunner.runLocalCommit`. In local default
mode this keeps the code path aligned with the rest of the service. In transaction-required deployments, Mongo
transaction manager support provides atomic case state + audit persistence.

FDP-42 analyst lifecycle atomicity requires Mongo transactions. Bank-grade case+audit atomic rollback requires
`app.regulated-mutations.transaction-mode=REQUIRED`. If transaction mode is `OFF`, FDP-42 must not claim rollback
atomicity. Integration tests prove atomicity using `MongoTransactionManager` and transaction-mode `REQUIRED`.

This transaction runner is used only as the local Mongo transaction boundary for FDP-42 lifecycle operations. It does
not make those operations regulated commands, replayable commands, lease-fenced commands, or externally finalized
commands.

## Read And Ingestion Boundaries

GET/list/search endpoints are pure reads and do not refresh or persist missing transaction details. System-generated
case candidate ingestion happens through `handleScoredTransaction(...)`, not through read endpoints. That ingestion
path is event-derived enrichment and is not an analyst lifecycle mutation, investigator decision, or FDP-42 analyst
audit claim.

## Invariants

- Case creation requires at least one alert id.
- Status transitions go through `FraudCaseTransitionPolicy`.
- Closed cases cannot be modified except explicit reopen.
- `RESOLVED` cases remain mutable until they are explicitly closed.
- Notes, decisions, and audit entries have no update/delete endpoint.
- Manual create links alerts through `linkedAlertIds`; it does not populate `transactionIds` with alert ids.
- Audit details are sanitized before append and exclude raw request payloads, idempotency keys, lease owners, payload
  hashes, exceptions, and stack traces.
- Append-only is enforced by application API shape and architecture tests; it is not WORM storage or legal
  notarization.
- Existing regulated mutation semantics for legacy fraud-case `PATCH` remain unchanged.
