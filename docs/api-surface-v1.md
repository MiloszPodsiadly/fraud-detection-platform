# API Surface v1

FDP-11 freezes the public HTTP API surface for local services without changing scoring, governance, security, or workflow behavior.

## Compatibility Rules

- Existing response fields keep their names, meanings, and JSON types.
- New response fields must be optional for clients.
- Existing endpoints are not wrapped in a new top-level `data` or `metadata` object unless a future versioned endpoint is introduced.
- Error responses use the platform `timestamp/status/error/message/details` envelope.
- Validation details are always returned in `details`; clients must not depend on framework exception text.
- Operational endpoints such as health and metrics remain operational contracts, not business APIs.

## ML Inference Service

Base URL in Docker: `http://ml-inference-service:8090`

| Method | Path | Contract |
| --- | --- | --- |
| `POST` | `/v1/fraud/score` | Scores a Java-enriched feature snapshot. Success shape is locked by `fraud_score_response.schema.json`. |
| `GET` | `/health` | Runtime health and active model identity. |
| `GET` | `/metrics` | Prometheus metrics. |
| `GET` | `/governance/model` | Active model metadata, reference profile summary, inference profile summary, drift summary. |
| `GET` | `/governance/model/current` | Read-only active model lifecycle metadata. |
| `GET` | `/governance/model/lifecycle` | Bounded read-only lifecycle event history. |
| `GET` | `/governance/profile/reference` | Reference profile details. |
| `GET` | `/governance/profile/inference` | Process-local aggregate inference profile. |
| `GET` | `/governance/drift` | Aggregate drift status. |
| `GET` | `/governance/drift/actions` | Advisory-only operator guidance. |
| `GET` | `/governance/advisories` | Bounded governance advisory event history. Success shape is locked by `governance_advisories_response.schema.json`. |
| `GET` | `/governance/history` | Bounded governance snapshot history. |

The ML OpenAPI reference is `docs/openapi/ml-inference-service.openapi.yaml`.

## Java Services

Transaction ingest service:

| Method | Path | Contract |
| --- | --- | --- |
| `POST` | `/api/v1/transactions` | Accepts external transaction ingestion requests. |

Transaction simulator service:

| Method | Path | Contract |
| --- | --- | --- |
| `POST` | `/api/v1/replay/start` | Starts synthetic replay. |
| `POST` | `/api/v1/replay/stop` | Stops synthetic replay. |
| `GET` | `/api/v1/replay/status` | Returns replay runtime status. |

Alert service:

| Method | Path | Contract |
| --- | --- | --- |
| `GET` | `/api/v1/alerts` | Lists alert queue entries. |
| `GET` | `/api/v1/alerts/{alertId}` | Returns one alert. |
| `GET` | `/api/v1/alerts/{alertId}/assistant-summary` | Returns read-only analyst assistant summary. |
| `POST` | `/api/v1/alerts/{alertId}/decision` | Records analyst decision. |
| `GET` | `/api/v1/fraud-cases` | Lists fraud cases. |
| `GET` | `/api/v1/fraud-cases/{caseId}` | Returns one fraud case. |
| `PATCH` | `/api/v1/fraud-cases/{caseId}` | Updates fraud case status/assignment fields. |
| `GET` | `/api/v1/transactions/scored` | Lists scored transaction projections. |

## Error Contract

All normalized local API errors use:

```json
{
  "timestamp": "2026-04-25T15:42:10.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Malformed JSON request.",
  "details": []
}
```

See `docs/api-error-contract.md` for field rules and non-leakage requirements.
