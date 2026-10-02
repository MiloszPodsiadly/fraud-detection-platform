# Fraud Case API Cleanup

Status: intentional breaking API surface cleanup.

The current API removes unused FraudCase HTTP handlers and retired lifecycle service, DTO, and local lifecycle-idempotency
internals. The retained product flow remains work queue, detail, regulated update, and bounded evidence reads.

## Removed Routes

| Removed route | Replacement |
| --- | --- |
| `GET /api/v1/fraud-cases` | `GET /api/v1/fraud-cases/work-queue` |
| `POST /api/v1/fraud-cases` | None; standalone create is not a current product API. |
| `POST /api/v1/fraud-cases/{caseId}/assign` | `PATCH /api/v1/fraud-cases/{caseId}` where applicable. |
| `POST /api/v1/fraud-cases/{caseId}/notes` | None; standalone notes have no current product replacement. |
| `POST /api/v1/fraud-cases/{caseId}/decisions` | `PATCH /api/v1/fraud-cases/{caseId}` where applicable. |
| `POST /api/v1/fraud-cases/{caseId}/transition` | `PATCH /api/v1/fraud-cases/{caseId}` where applicable. |
| `POST /api/v1/fraud-cases/{caseId}/close` | `PATCH /api/v1/fraud-cases/{caseId}` where applicable. |
| `POST /api/v1/fraud-cases/{caseId}/reopen` | `PATCH /api/v1/fraud-cases/{caseId}` where applicable. |
| `GET /api/v1/fraud-cases/{caseId}/audit` | None; standalone audit trail has no current product replacement. |
| `/api/fraud-cases/**` | Supported versioned routes only; the compatibility handler is removed. |

## Retained Routes

- `GET /api/v1/fraud-cases/work-queue`
- `GET /api/v1/fraud-cases/work-queue/summary`
- `GET /api/v1/fraud-cases/{caseId}`
- `PATCH /api/v1/fraud-cases/{caseId}`
- `GET /api/v1/fraud-cases/{caseId}/evidence-summary`
- `GET /api/v1/fraud-cases/{caseId}/evidence-timeline`

## Replacement Guidance

List and search clients use the bounded work queue. A lifecycle action maps to regulated `PATCH` only where the
retained update contract supports that state change. Standalone notes, decisions, and audit-trail reads have no
current product replacement. Unversioned compatibility calls are removed; clients use retained versioned routes.

## Historical Unversioned Route Behavior

At an earlier migration boundary, authenticated requests to `/api/fraud-cases/**` returned `410 Gone` with `code:LEGACY_FRAUD_CASE_ROUTE_REMOVED`;
unauthenticated requests failed at the authentication boundary. The compatibility handler has since been removed.
Clients must not rely on `410 Gone`; that response is historical. Removed unversioned routes now use normal
unknown-route and security fallback behavior.

## Unaffected APIs

Alerts API, Scored Transactions API, Suspicious Transactions API including
`GET /internal/suspicious-transactions/summary`, the suspicious linked-alert resolver, Evidence Summary, Evidence
Timeline, and governance/reporting routes used by the current frontend are unaffected by this cleanup.
