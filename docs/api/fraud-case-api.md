# Fraud Case API

Status: current API summary.

Base paths:

- `/api/fraud-cases`
- `/api/v1/fraud-cases`

Both paths are protected by the same `alert-service` RBAC rules.

FDP-43 lifecycle endpoints are local audited lifecycle endpoints with local idempotency. They reuse shared
idempotency primitives from the existing regulated mutation model, but they are not routed through
`RegulatedMutationCoordinator`, not FDP-29 evidence-gated finalize endpoints, not lease-fenced commands, not global
exactly-once execution, and not external finality.

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

Every local lifecycle `POST` requires `X-Idempotency-Key`. Missing keys return `400` with
`code:MISSING_IDEMPOTENCY_KEY`; invalid keys return `400` with `code:INVALID_IDEMPOTENCY_KEY`.

FDP-43 local lifecycle idempotency reuses shared canonical hashing, key validation, and conflict semantics from the
regulated mutation architecture. It does not route fraud-case lifecycle operations through
`RegulatedMutationCoordinator`.

- `X-Idempotency-Key` is globally unique within the fraud-case lifecycle idempotency domain. The stored record is
  looked up by key hash; action, backend actor, scope, and request hash are conflict-checked claim fields.
- Same key + same payload + same resolved backend actor/action/scope returns the stored response snapshot and does not
  re-execute the lifecycle mutation or append another audit entry.
- Same key + different payload, actor, action, or scope returns `409` with `code:IDEMPOTENCY_KEY_CONFLICT`.
- An in-progress same-key operation returns `409` with `code:IDEMPOTENCY_KEY_IN_PROGRESS`.
- Concurrent same-key requests do not duplicate lifecycle mutation, audit entry, or idempotency record.
  Depending on timing, the competing request may receive a stable replay response or an idempotency-in-progress
  conflict. Clients should retry later with the same idempotency key after an in-progress response.
- FDP-43 guarantees side-effect idempotency for local lifecycle operations, not deterministic concurrent response
  timing.
- Idempotency key hashes and request hashes are stored; raw idempotency keys and raw request payloads are not stored
  or exposed.
- The idempotency record, lifecycle mutation, and audit append commit or roll back together when Mongo transactions
  are enabled with `app.regulated-mutations.transaction-mode=REQUIRED`.
- Idempotency records are retained for `app.fraud-cases.idempotency.retention` (`PT24H` by default). After the
  retention window and eventual Mongo TTL deletion, the same key may execute as a new lifecycle operation.
- Replay requires a valid authenticated/resolved actor context. If actor resolution fails before lookup, the request
  fails before replay and does not mutate.
- Response snapshots are bounded at runtime. If the safe replay snapshot exceeds the configured limit, the local
  lifecycle operation fails closed with `code:IDEMPOTENCY_SNAPSHOT_TOO_LARGE` and the idempotency record, lifecycle
  mutation, and audit append roll back together.
- Public HTTP lifecycle POSTs use only idempotency-key service overloads. No missing `X-Idempotency-Key` request
  reaches the lifecycle mutation path.

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

Stable error details include `reason:FRAUD_CASE_VALIDATION_FAILED`, `reason:FRAUD_CASE_NOT_FOUND`,
`reason:FRAUD_CASE_LIFECYCLE_CONFLICT`, `code:MISSING_IDEMPOTENCY_KEY`, `code:INVALID_IDEMPOTENCY_KEY`,
`code:IDEMPOTENCY_KEY_CONFLICT`, `code:IDEMPOTENCY_KEY_IN_PROGRESS`, and
`code:IDEMPOTENCY_SNAPSHOT_TOO_LARGE`.

## Duplicate Submit Semantics

With the same `X-Idempotency-Key`, same resolved backend actor, same action/scope, and same payload, repeating `notes` or `decisions` replays the stored response and
does not create another append-only record or audit entry. With a different idempotency key, each POST is a new local
lifecycle command and follows normal lifecycle policy.

`RESOLVED` cases remain mutable until `CLOSED`; notes, decisions, and assignment remain allowed while the case is
resolved.

## System-Generated Case Candidate Ingestion

Transaction-scored event ingestion is separate from these analyst lifecycle endpoints. It may create or enrich
system-generated case candidates, but it is not an investigator lifecycle action and does not claim FDP-42 analyst
lifecycle audit semantics.

## Non-Claims

This API does not change Kafka/outbox semantics, does not replace the regulated mutation safety model, does not add
ML behavior, and does not add release-governance controls. It also does not claim FDP-29 evidence-gated finalize,
lease-fenced execution, global exactly-once delivery, distributed ACID, distributed locking, WORM storage, legal
notarization, Kafka exactly-once, or external finality.
