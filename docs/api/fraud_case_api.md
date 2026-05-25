# Fraud Case API

Status: current API summary.

## FDP-81 Surface Decision

FDP-81 is intentional API surface cleanup. The current React analyst console runtime is the source of truth for the
supported FraudCase product surface. Old lifecycle, audit-trail, list/search, and unversioned compatibility handlers
are no longer exposed as HTTP API routes.

This is not a no-behavior-change cleanup. It narrows the supported backend API while preserving the frontend-backed
read and update flow.

## Supported Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/fraud-cases/work-queue` | Read the bounded investigator work queue. |
| `GET` | `/api/v1/fraud-cases/work-queue/summary` | Read the global work queue count summary. |
| `GET` | `/api/v1/fraud-cases/{caseId}` | Read one fraud case detail. |
| `PATCH` | `/api/v1/fraud-cases/{caseId}` | Update the current fraud-case status/assignment decision surface through a regulated mutation command. |
| `GET` | `/api/v1/fraud-cases/{caseId}/evidence-summary` | Read bounded evidence-summary context from linked alert evidence snapshots. |
| `GET` | `/api/v1/fraud-cases/{caseId}/evidence-timeline` | Read bounded derived evidence chronology from linked alert evidence snapshots. |

Authorities:

- Read endpoints require `fraud-case:read`.
- `PATCH` requires `fraud-case:update` and `X-Idempotency-Key`.
- There is no currently exposed fraud-case lifecycle audit-history endpoint.

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

## Patch Semantics

The current `PATCH` route uses the regulated mutation contract documented in
[API surface v1](api_surface_v1.md) and [Public API semantics](public_api_semantics.md). It requires
`X-Idempotency-Key`, persists only bounded canonical intent fields, and returns `UpdateFraudCaseResponse`.
Non-terminal operation states do not echo requested target business fields as committed values.

## Evidence Summary Semantics

- The evidence summary is a read-only projection over linked alert evidence snapshots.
- It returns bounded reason-code, evidence type, severity, source and status context only.
- It does not expose raw alert ids, customer/account identifiers, transaction ids, feature snapshots or raw model
  payloads.
- It does not mutate fraud-case state, create analyst decisions, publish Kafka events or claim a final outcome.
- Truncated or incomplete linked-alert coverage is represented as partial rather than available evidence.

## Evidence Timeline Semantics

- The evidence timeline is read-only investigation chronology derived from linked alert read data.
- It does not represent an audit trail, workflow history, confirmed fraud, legal proof or final outcome.
- Response-local event keys are not persistent evidence identifiers.
- Missing or bounded source data remains explicitly partial or truncated.

## Unchanged Adjacent Product Surface

FDP-81 does not change alerts, scored transactions, suspicious transactions, the linked-alert resolver or its
client-selected `alertId` rejection guard, or governance endpoints used by Reports and Compliance workspaces.

## Non-Claims

This API does not claim evidence-gated finality, lease-fenced replay safety, distributed ACID, exactly-once delivery,
WORM storage, legal notarization, bank certification or external finality.
