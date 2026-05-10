# Fraud Case API

Status: current API summary.

Base paths:

- `/api/fraud-cases`
- `/api/v1/fraud-cases`

Both paths are protected by the same `alert-service` RBAC rules.

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

## Non-Claims

This API does not change Kafka/outbox semantics, does not replace the regulated mutation safety model, does not add
ML behavior, and does not add advanced RBAC beyond existing `fraud-case:read` and `fraud-case:update` authorities.
