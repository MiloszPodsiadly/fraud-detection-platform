# Investigation Evidence Platform Cleanup Inventory

Status: current cleanup inventory.

## FDP-81 - Frontend-Aligned Backend API Surface Cleanup

Decision: the current React analyst console runtime is the source of truth for the supported product API surface.
This branch intentionally removes backend API routes that are no longer used by the current frontend product flow.
This is hard API surface cleanup; it is not no-behavior-change cleanup.

## Kept Active Surfaces

- `GET /api/v1/alerts`
- `GET /api/v1/alerts/{alertId}`
- `GET /api/v1/alerts/{alertId}/assistant-summary`
- `POST /api/v1/alerts/{alertId}/decision`
- `GET /api/v1/transactions/scored`
- `GET /internal/suspicious-transactions`
- `GET /internal/suspicious-transactions/summary`
- `GET /internal/suspicious-transactions/{suspiciousTransactionId}`
- `GET /internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`
- The suspicious linked-alert client-selected `alertId` rejection guard.
- `GET /api/v1/fraud-cases/work-queue`
- `GET /api/v1/fraud-cases/work-queue/summary`
- `GET /api/v1/fraud-cases/{caseId}`
- `PATCH /api/v1/fraud-cases/{caseId}`
- `GET /api/v1/fraud-cases/{caseId}/evidence-summary`
- `GET /api/v1/fraud-cases/{caseId}/evidence-timeline`
- Governance endpoints used by Reports and Compliance workspaces.

## Retained After Suspicious Summary Ownership Proof

- `GET /internal/suspicious-transactions/summary` has a current React runtime owner: `WorkspaceDashboardShell`
  mounts `useWorkspaceCounters`, which calls `getSuspiciousTransactionSummary` when
  `SUSPICIOUS_TRANSACTION_READ` is available, and `WorkspaceNavigation` renders the resulting suspicious signal total.
- This endpoint is retained because of current product runtime usage, not because tests, documentation or security
  rules mention it.
- `GET /internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert` remains an active backend-resolved
  security/read surface; it rejects a client-selected `alertId` and requires both `SUSPICIOUS_TRANSACTION_READ` and
  `ALERT_READ`.

## Removed Old FraudCase Lifecycle Surface

- `GET /api/v1/fraud-cases`
- `POST /api/v1/fraud-cases`
- `POST /api/v1/fraud-cases/{caseId}/assign`
- `POST /api/v1/fraud-cases/{caseId}/notes`
- `POST /api/v1/fraud-cases/{caseId}/decisions`
- `POST /api/v1/fraud-cases/{caseId}/transition`
- `POST /api/v1/fraud-cases/{caseId}/close`
- `POST /api/v1/fraud-cases/{caseId}/reopen`
- `GET /api/v1/fraud-cases/{caseId}/audit`
- `/api/fraud-cases`
- `/api/fraud-cases/**`

## Additional Cleanup

- Removed duplicate npm script alias `check:scope:fdp53`; `check:fdp53-scope` remains authoritative.
- Removed the unowned FraudCase lifecycle facade support, lifecycle/idempotency implementation, local note/decision/
  audit persistence, retired DTOs and the lifecycle idempotency metric after a no-current-owner proof.
- Kept regulated `PATCH`, candidate ingestion, work queue, detail and evidence read implementations with dedicated
  current-surface and removed-surface guards.
