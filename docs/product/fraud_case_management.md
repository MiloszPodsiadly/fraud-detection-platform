# Fraud Case Management

Status: current product-domain source of truth.

## Current Product Flow

FDP-81 aligns backend exposure to the current React analyst console. The supported FraudCase experience is a bounded
work queue, case detail, regulated `PATCH` update, evidence summary, and evidence timeline.

This is intentional API surface cleanup, not a no-behavior-change cleanup. Older standalone lifecycle actions,
search/list handling, audit-trail reads and unversioned compatibility routing are not part of the current product API.

## Current Capabilities

| Capability | Current behavior |
| --- | --- |
| Work queue | Reads bounded analyst queue rows with cursor-aware query controls. |
| Work queue summary | Returns the global fraud-case count for navigation context. |
| Case detail | Reads one current fraud-case record. |
| Case update | Uses the regulated `PATCH` command with `X-Idempotency-Key`. |
| Evidence summary | Reads bounded linked-alert evidence context without exposing raw source payloads. |
| Evidence timeline | Reads bounded chronology context; it is not audit trail or complete case history. |

## API And Authority Boundary

| Surface | Required authority |
| --- | --- |
| Work queue, summary, case detail, evidence summary and evidence timeline | `fraud-case:read` |
| Regulated case `PATCH` update | `fraud-case:update` |

The current HTTP contract is documented in [Fraud Case API](../api/fraud_case_api.md). The update command requires
`X-Idempotency-Key`; read surfaces do not mutate state.

## Removed In FDP-81

| Removed route | Replacement | Notes |
| --- | --- | --- |
| `GET /api/v1/fraud-cases` | `GET /api/v1/fraud-cases/work-queue` | List/search is replaced by the bounded work queue. |
| `POST /api/v1/fraud-cases` | None | Standalone create is removed from the current product surface. |
| `POST /api/v1/fraud-cases/{caseId}/assign` | `PATCH /api/v1/fraud-cases/{caseId}` where applicable | No standalone assign endpoint. |
| `POST /api/v1/fraud-cases/{caseId}/notes` | None | Notes are not a current product API. |
| `POST /api/v1/fraud-cases/{caseId}/decisions` | `PATCH /api/v1/fraud-cases/{caseId}` where applicable | Standalone decision route removed. |
| `POST /api/v1/fraud-cases/{caseId}/transition` | `PATCH /api/v1/fraud-cases/{caseId}` where applicable | Lifecycle transition route removed. |
| `POST /api/v1/fraud-cases/{caseId}/close` | `PATCH /api/v1/fraud-cases/{caseId}` where applicable | Close route removed. |
| `POST /api/v1/fraud-cases/{caseId}/reopen` | `PATCH /api/v1/fraud-cases/{caseId}` where applicable | Reopen route removed. |
| `GET /api/v1/fraud-cases/{caseId}/audit` | None | Standalone audit-trail read removed. |
| `/api/fraud-cases/**` | Supported versioned routes only | Compatibility handler removed. |

`GET /internal/suspicious-transactions/summary` remains supported and is not part of this removal.

## Read Surface Constraints

- Evidence surfaces remain bounded and read-only.
- Evidence summary and timeline do not expose raw alert identifiers, raw payloads, customer/account identifiers or
  analyst workflow controls.
- Evidence timeline is not an audit trail, event store history, workflow history, legal proof or final outcome.
- Missing or truncated linked-alert source data remains explicit as partial/truncated context.

## System Event Ingestion

Internal scored-transaction ingestion may continue to create or enrich fraud-case candidate state. It is not an
analyst lifecycle mutation and is not exposed by the removed HTTP lifecycle surface.

## Unchanged Product Boundaries

Alerts, scored transaction reads, SuspiciousTransaction read flows, the backend-owned linked-alert resolver and its
client-selected `alertId` rejection guard, and governance Reports/Compliance endpoints remain unchanged.

## Related Documents

- [Fraud Case API](../api/fraud_case_api.md)
- [Fraud Case Evidence Summary](fraud_case_evidence_summary.md)
- [Fraud Case Evidence Timeline](fraud_case_evidence_timeline.md)
- [Endpoint authorization map](../security/endpoint_authorization_map.md)

## Non-Claims

Fraud Case Management is not evidence-gated, not lease-fenced replay safety, not a regulated mutation finality claim,
and not external finality. It does not provide distributed ACID, distributed locking, global exactly-once execution,
WORM storage, legal notarization, bank certification or production enablement.
