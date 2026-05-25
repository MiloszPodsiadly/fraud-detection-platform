# Fraud Case Management Architecture

Status: current architecture note.

## Boundary

FDP-81 reduces FraudCase to the product surface owned by the current analyst console. `alert-service` keeps candidate
ingestion, bounded reads, evidence projections and regulated `PATCH`. It removes the retired list/search, standalone
lifecycle and fraud-case audit-history implementation below the controller layer.

Current HTTP surface:

- `GET /api/v1/fraud-cases/work-queue`
- `GET /api/v1/fraud-cases/work-queue/summary`
- `GET /api/v1/fraud-cases/{caseId}`
- `PATCH /api/v1/fraud-cases/{caseId}`
- `GET /api/v1/fraud-cases/{caseId}/evidence-summary`
- `GET /api/v1/fraud-cases/{caseId}/evidence-timeline`

## Current Flow

```text
TransactionScoredEvent -> FraudCaseManagementService.handleScoredTransaction -> FraudCaseRepository
FraudCaseController -> FraudCaseManagementService -> FraudCaseQueryService / FraudCaseRepository
FraudCaseController PATCH -> FraudCaseManagementService -> RegulatedMutationCoordinator
Evidence controllers -> bounded read projection services
```

`FraudCaseQueryService` owns current detail and work queue reads only. `FraudCaseManagementService` owns ingestion
and routes the current update through `RegulatedMutationCoordinator`. The removed lifecycle service, its local
idempotency/replay records, note/decision/audit repositories and retired DTOs have no runtime owner and do not exist
in the current implementation.

## Security And Observability

- Read endpoints require the existing fraud-case read authority; `PATCH` requires the update authority and
  `X-Idempotency-Key`.
- The `PATCH` command retains the regulated mutation audit and recovery contract; FDP-81 does not create a second
  mutation framework.
- Security-denial telemetry classifies only current FraudCase route families under the bounded `fraud_case` group.
  Retired lifecycle paths are not classified as active FraudCase routes.
- The retired local lifecycle idempotency metric is removed with the subsystem that emitted it.

## Invariants

- Work queue, detail and evidence reads do not mutate fraud-case state.
- System-generated candidate ingestion is not an analyst lifecycle mutation.
- Evidence summary and timeline are bounded read projections, not workflow history or audit history.
- Current regulated `PATCH` semantics remain unchanged.
- This architecture does not claim external finality, global exactly-once execution, distributed ACID, WORM
  storage, legal notarization, bank certification or production enablement.
