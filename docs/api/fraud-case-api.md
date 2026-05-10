# Fraud Case API

Status: current API summary.

Base paths:

- `/api/fraud-cases`
- `/api/v1/fraud-cases`

Both paths are protected by the same `alert-service` RBAC rules.

FDP-42 endpoints are local audited lifecycle endpoints. They are not `RegulatedMutationCoordinator` endpoints, not
FDP-29 evidence-gated finalize endpoints, not lease-fenced commands, not replay-safe commands, and not external
finality.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/fraud-cases` | Create a fraud case from alert ids. |
| `GET` | `/api/fraud-cases/{caseId}` | Read case details. |
| `GET` | `/api/fraud-cases` | Search cases. |
| `POST` | `/api/fraud-cases/{caseId}/assign` | Assign or reassign investigator. |
| `POST` | `/api/fraud-cases/{caseId}/notes` | Append investigator note. |
| `POST` | `/api/fraud-cases/{caseId}/decisions` | Append investigator decision. |
| `POST` | `/api/fraud-cases/{caseId}/transition` | Change status through lifecycle policy. |
| `POST` | `/api/fraud-cases/{caseId}/close` | Close a resolved case. |
| `POST` | `/api/fraud-cases/{caseId}/reopen` | Reopen a closed case. |
| `GET` | `/api/fraud-cases/{caseId}/audit` | Read audit history. |

Authorities:

- `GET /fraud-cases` and `GET /fraud-cases/{caseId}` require `fraud-case:read`.
- Lifecycle `POST` endpoints require `fraud-case:update`.
- `GET /fraud-cases/{caseId}/audit` requires `fraud-case:audit:read` and intentionally returns audit `actorId`.

List semantics:

- `GET /fraud-cases` is paginated. The API accepts `page` and `size`; `size` is capped at 100.

## POST Endpoint Idempotency

For every local lifecycle `POST`: Idempotency: not idempotent unless explicitly stated. Do not blindly retry after
ambiguous network failure.

- `POST /fraud-cases`: Idempotency: not idempotent unless explicitly stated. Do not blindly retry after ambiguous
  network failure. Repeated submit creates an independent case.
- `POST /fraud-cases/{caseId}/assign`: Idempotency: not idempotent unless explicitly stated. Do not blindly retry
  after ambiguous network failure. Same assignee assignment is accepted and audited as reassignment.
- `POST /fraud-cases/{caseId}/notes`: Idempotency: not idempotent unless explicitly stated. Do not blindly retry
  after ambiguous network failure. Repeated submit appends another note record and audit entry.
- `POST /fraud-cases/{caseId}/decisions`: Idempotency: not idempotent unless explicitly stated. Do not blindly
  retry after ambiguous network failure. Repeated submit appends another decision record and audit entry.
- `POST /fraud-cases/{caseId}/transition`: Idempotency: not idempotent unless explicitly stated. Do not blindly
  retry after ambiguous network failure. Repeated submit follows the lifecycle policy and may return `409`.
- `POST /fraud-cases/{caseId}/close`: Idempotency: not idempotent unless explicitly stated. Do not blindly retry
  after ambiguous network failure. Repeated submit may return `409` once the case is no longer closable.
- `POST /fraud-cases/{caseId}/reopen`: Idempotency: not idempotent unless explicitly stated. Do not blindly retry
  after ambiguous network failure. Repeated submit may return `409` once the case is no longer reopenable.

## Sample Requests

```json
{
  "alertIds": ["alert-1", "alert-2"],
  "priority": "HIGH",
  "riskLevel": "CRITICAL",
  "reason": "Manual investigation required",
  "actorId": "analyst-1"
}
```

```json
{
  "assignedInvestigatorId": "investigator-1",
  "actorId": "lead-1"
}
```

```json
{
  "decisionType": "FRAUD_CONFIRMED",
  "summary": "Confirmed after investigator review.",
  "actorId": "investigator-1"
}
```

## Error Semantics

- Invalid request bodies return `400`.
- Missing authentication or actor identity returns `401`.
- Missing cases or referenced alerts return `404`.
- Forbidden lifecycle mutations return `409`.
- Unhandled internals return stable JSON `500` without raw stack traces.

Stable FDP-42 error details include `reason:FRAUD_CASE_VALIDATION_FAILED`,
`reason:FRAUD_CASE_NOT_FOUND`, and `reason:FRAUD_CASE_LIFECYCLE_CONFLICT`.

## Duplicate Submit Semantics

The local lifecycle POST endpoints are not idempotent. Repeating `notes` or `decisions` creates another append-only
record and audit entry. Clients must not blindly retry these POSTs after ambiguous transport failures. Repeated close
or reopen follows the lifecycle policy and returns `409` once the status no longer allows the transition.
Reassigning the same investigator is accepted and audited as `CASE_REASSIGNED`. Repeating a create request with the
same alert ids creates an independent case unless a future idempotency contract is introduced.

`RESOLVED` cases remain mutable until `CLOSED`; notes, decisions, and assignment remain allowed while the case is
resolved.

## System-Generated Case Candidate Ingestion

Transaction-scored event ingestion is separate from these analyst lifecycle endpoints. It may create or enrich
system-generated case candidates, but it is not an investigator lifecycle action and does not claim FDP-42 analyst
lifecycle audit semantics.

## Non-Claims

This API does not change Kafka/outbox semantics, does not replace the regulated mutation safety model, does not add
ML behavior, and does not add release-governance controls. It also does not claim evidence-gated finalize,
lease-fenced execution, replay safety, exactly-once delivery, or external finality.
