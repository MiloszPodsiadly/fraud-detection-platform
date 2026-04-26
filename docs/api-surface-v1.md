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
The alert-service governance audit OpenAPI reference is `docs/openapi/alert-service.openapi.yaml`.

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
| `GET` | `/governance/advisories` | Lists governance advisory events enriched with read-time lifecycle status. |
| `GET` | `/governance/advisories/analytics` | Returns bounded read-only audit analytics derived from advisory and audit history. |
| `GET` | `/governance/advisories/{event_id}` | Returns one governance advisory event enriched with read-time lifecycle status. |
| `GET` | `/governance/advisories/{event_id}/audit` | Returns bounded newest-first human-review audit history for one governance advisory event. |
| `POST` | `/governance/advisories/{event_id}/audit` | Appends one authenticated human-review audit entry for a governance advisory event. |

Governance advisory audit and lifecycle projection endpoints are owned by `alert-service`, not `ml-inference-service`, because lifecycle status is derived from authenticated human-review audit history. They do not mutate advisory events, scoring, model behavior, retraining, rollback, or fraud decisioning.

Advisory lifecycle status is a read-time projection:

- `OPEN`: no audit events exist.
- `ACKNOWLEDGED`: latest audit decision is `ACKNOWLEDGED`.
- `NEEDS_FOLLOW_UP`: latest audit decision is `NEEDS_FOLLOW_UP`.
- `DISMISSED_AS_NOISE`: latest audit decision is `DISMISSED_AS_NOISE`.

Only the latest audit event matters. Lifecycle status is not persisted independently, is not workflow state, has no SLA, and triggers no automation.

Filtering by `lifecycle_status` applies to the bounded advisory result set. It does not guarantee global completeness.

Audit analytics are read-only and derived:

- `GET /governance/advisories/analytics?window_days=7`
- `window_days` defaults to `7` and is capped at `30`.
- `totals.advisories` is the number of distinct `advisory_event_id` values in the bounded advisory projection window.
- `totals.reviewed` means those advisories with at least one matching audit event; `totals.open` means zero matching audit events.
- `decision_distribution` uses the latest audit decision for reviewed advisories in that same projection window.
- `lifecycle_distribution` uses read-time lifecycle enrichment of that same projection and sums to `totals.advisories`.
- `review_timeliness` samples only valid non-negative first-review durations and reports `LOW_CONFIDENCE` when fewer than five samples exist.
- Status is `AVAILABLE` when advisory and audit sources are both readable, `PARTIAL` when one source is degraded or the audit scan limit is exceeded, and `UNAVAILABLE` when both sources are unavailable.
- Analytics operate on bounded time windows, cap audit scans with `GOVERNANCE_AUDIT_ANALYTICS_MAX_AUDIT_EVENTS`, and do not guarantee global completeness.
- Analytics do not persist aggregates, enforce SLA, trigger actions, or change scoring/model behavior.

### API Stability

`GET /governance/advisories/analytics` is considered stable. Breaking changes to existing fields, enums, or meanings require a version bump. The optional `reason` field may appear only for `PARTIAL` or `UNAVAILABLE` responses and is limited to `AUDIT_LIMIT_EXCEEDED`, `AUDIT_UNAVAILABLE`, or `ADVISORY_UNAVAILABLE`.

Advisory list filters:

- `severity`
- `model_version`
- `lifecycle_status`
- `limit`

POST request:

```json
{
  "decision": "ACKNOWLEDGED",
  "note": "Reviewed by operator"
}
```

Allowed `decision` values:

- `ACKNOWLEDGED`
- `NEEDS_FOLLOW_UP`
- `DISMISSED_AS_NOISE`

Frontend-provided `actor_id`, actor roles, or model metadata fields are rejected or ignored by contract; actor attribution is backend-derived. `note` is optional and capped at 500 characters.

GET response:

```json
{
  "advisory_event_id": "event-1",
  "status": "AVAILABLE",
  "audit_events": []
}
```

`GET` may return `status=UNAVAILABLE` with an empty `audit_events` array when audit storage is unavailable. `POST` fails clearly with the platform error envelope when persistence or advisory lookup is unavailable; explicit operator write intent is not silently dropped.

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
