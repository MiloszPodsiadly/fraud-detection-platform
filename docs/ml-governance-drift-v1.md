# ML Governance And Drift v1

FDP-7 adds the first bounded ML governance layer for `ml-inference-service`.

This is runtime oversight, not a full MLOps platform. It exposes model lineage, read-only model lifecycle visibility, aggregate reference and inference profiles, simple threshold drift checks, and low-cardinality Prometheus signals. It does not change scoring, alert thresholds, fraud decisions, Java fallback behavior, model switching, rollback, retraining, or model quality validation.

## Purpose

Operators need to answer:

- which model version is active
- what baseline distribution the runtime is compared against
- what aggregate distribution live inference is seeing
- whether input features or score outputs have shifted
- what to inspect when drift appears

## Current Capabilities

- `GET /governance/model` exposes active model metadata and artifact lineage.
- `GET /governance/model/current` exposes the current model lifecycle metadata.
- `GET /governance/model/lifecycle` exposes bounded read-only lifecycle event history.
- `GET /governance/profile/reference` exposes the loaded aggregate reference profile.
- `GET /governance/profile/inference` exposes process-local aggregate inference stats.
- `GET /governance/drift` compares inference stats against the reference profile and returns status, confidence, sample size, lifecycle status, and bounded signal details.
- `GET /governance/drift/actions` interprets drift into advisory operator actions and escalation guidance.
- `GET /governance/advisories` exposes bounded governance advisory events for operators.
- `GET /governance/history` exposes bounded persisted governance snapshot history when MongoDB is available.
- `/metrics` exposes low-cardinality governance gauges and counters.

## Reference Profile

Current profile location:

```text
ml-inference-service/app/governance/reference_profile.local.json
```

The current baseline is a deterministic synthetic/local profile for model version `2026-04-21.trained.v1`, generated from the existing synthetic fraud behavior pipeline. It contains only aggregate statistics:

- model name and version
- profile version and generation timestamp
- `source`: `synthetic`, `training`, or `evaluation`
- `reference_quality`: `SYNTHETIC`, `LIMITED`, or `PRODUCTION`
- `data_window`, `generated_by`, and `sample_size`
- training mode and feature contract version
- numeric feature count, mean, std, min, max, p50, p90, p95
- score distribution count, mean, std, min, max, p50, p90, p95
- high-risk rate and risk-level distribution

It does not contain raw training rows, transaction payloads, identifiers, timestamps, or examples.

Important: the shipped profile has `source=synthetic` and `reference_quality=SYNTHETIC`. It is useful for local validation and contract testing, but it is not suitable for production drift decisions. Production drift decisions require a production-quality reference profile generated from an approved production baseline window.

## Inference Profile

The inference profile is process-local and in-memory. It updates only after successful `POST /v1/fraud/score` responses.

Collected:

- `profile_started_at`
- `last_updated_at`
- `observation_count`
- `profile_uptime_seconds`
- selected normalized numeric feature aggregates
- missing or invalid feature counts
- fraud score distribution
- risk-level distribution

Not collected:

- raw request bodies
- transaction, customer, account, card, user, or correlation identifiers
- raw feature rows
- reason text in metrics

Reset behavior is explicit: the profile resets on restart. Tests can reset it in-process; there is no operational reset endpoint in v1.

## Governance Snapshot Persistence

FDP-7.1 persists aggregate governance snapshots in the existing platform MongoDB. This preserves governance history across `ml-inference-service` restarts without adding a new database technology.

Default configuration:

```text
MONGODB_URI=mongodb://mongodb:27017/fraud_governance
GOVERNANCE_SNAPSHOT_COLLECTION=ml_governance_snapshots
GOVERNANCE_SNAPSHOT_RETENTION_LIMIT=500
GOVERNANCE_SNAPSHOT_INTERVAL_REQUESTS=50
```

Collection:

```text
ml_governance_snapshots
```

Snapshot write strategy:

- A snapshot is attempted every `GOVERNANCE_SNAPSHOT_INTERVAL_REQUESTS` successful scoring requests.
- Default interval is `50`.
- Snapshot persistence is best-effort and never blocks or fails scoring.
- MongoDB outage, missing `pymongo`, write failure, index failure, or retention failure is logged as a warning-level governance event and reflected in low-cardinality metrics.

Document schema:

```json
{
  "snapshotId": "uuid",
  "createdAt": "datetime",
  "modelName": "python-logistic-fraud-model",
  "modelVersion": "2026-04-21.trained.v1",
  "referenceProfileId": "2026-04-25.synthetic.v1",
  "referenceProfile": {},
  "referenceQuality": "SYNTHETIC",
  "inferenceProfileSummary": {},
  "driftStatus": "UNKNOWN",
  "driftConfidence": "LOW",
  "driftSignalsSummary": {
    "signal_count": 0,
    "severity_counts": {},
    "strongest_signals": []
  },
  "observationCount": 0
}
```

Persisted snapshots do not include raw inference requests, raw feature rows, transaction IDs, customer IDs, account IDs, card IDs, user IDs, correlation IDs, full payloads, raw exception text, or unbounded arrays.

Indexes:

- `createdAt` descending
- `modelName`, `modelVersion`, `createdAt` descending

Retention:

- The service keeps the latest `GOVERNANCE_SNAPSHOT_RETENTION_LIMIT` snapshots per `modelName` and `modelVersion`.
- Default retention limit is `500`.
- Retention deletion is best-effort. Failure to delete stale snapshots does not affect scoring.

## Model Lifecycle Visibility

FDP-9 adds read-only model lifecycle visibility. It shows which model metadata the runtime loaded and gives operators bounded lifecycle context for drift triage. It does not switch models, retrain, rollback, approve models, validate model quality, or expose raw artifacts.

Current model endpoint:

```text
GET /governance/model/current
```

Example response:

```json
{
  "model_name": "python-logistic-fraud-model",
  "model_version": "2026-04-21.trained.v1",
  "model_family": "LOGISTIC_REGRESSION",
  "loaded_at": "2026-04-26T08:00:00+00:00",
  "artifact_path_or_id": "model_artifact.json",
  "artifact_source": "LOCAL_FILE",
  "artifact_checksum": "sha256:...",
  "feature_set_version": null,
  "training_mode": "production",
  "reference_profile_id": "2026-04-25.synthetic.v1",
  "runtime_environment": "ml-inference-service",
  "lifecycle_mode": "READ_ONLY"
}
```

Lifecycle history endpoint:

```text
GET /governance/model/lifecycle
```

Example response:

```json
{
  "status": "PARTIAL",
  "current_model": {},
  "count": 3,
  "retention_limit": 200,
  "lifecycle_events": [
    {
      "event_id": "uuid",
      "event_type": "MODEL_LOADED",
      "occurred_at": "2026-04-26T08:00:00+00:00",
      "model_name": "python-logistic-fraud-model",
      "model_version": "2026-04-21.trained.v1",
      "previous_model_version": null,
      "source": "model_runtime",
      "reason": "active model loaded by inference runtime",
      "metadata_summary": {
        "artifact_source": "LOCAL_FILE",
        "artifact_checksum": "sha256:...",
        "training_mode": "production",
        "reference_profile_id": "2026-04-25.synthetic.v1",
        "lifecycle_mode": "READ_ONLY"
      }
    }
  ]
}
```

Lifecycle event types:

- `MODEL_LOADED`
- `MODEL_METADATA_DETECTED`
- `REFERENCE_PROFILE_LOADED`
- `GOVERNANCE_HISTORY_AVAILABLE`
- `GOVERNANCE_HISTORY_UNAVAILABLE`

Persistence:

```text
MODEL_LIFECYCLE_COLLECTION=ml_model_lifecycle_events
MODEL_LIFECYCLE_RETENTION_LIMIT=200
```

Lifecycle events are persisted best-effort to `ml_model_lifecycle_events` when MongoDB is available. Indexes are created on `occurredAt` descending and `modelName`, `modelVersion`, `occurredAt` descending. If MongoDB is unavailable, the endpoint returns bounded in-memory lifecycle history with `status=PARTIAL`. Failure to persist lifecycle events never affects scoring.

Lifecycle records exclude raw artifact contents, raw requests, identifiers, secrets, absolute sensitive filesystem paths where avoidable, exception text, and unbounded arrays. Artifact checksums are metadata only.

## Drift Status

`GET /governance/drift` returns a normalized response:

```json
{
  "model": {},
  "reference_profile": {},
  "inference_profile": {},
  "drift": {
    "status": "UNKNOWN",
    "confidence": "LOW",
    "sample_size": 0,
    "reason": "insufficient_data",
    "inference_profile_status": "RESET_RECENTLY",
    "signals": [],
    "evaluated_at": "2026-04-25T00:00:00+00:00"
  }
}
```

Status:

- `UNKNOWN`: reference profile is unavailable or runtime sample size is below the guardrail.
- `OK`: checked signals are within configured thresholds.
- `WATCH`: weak drift signal or threshold breach that needs operator attention.
- `DRIFT`: strong threshold breach.

Confidence:

- `LOW`: fewer than `MIN_OBSERVATIONS`, newly reset profile, or synthetic reference profile.
- `MEDIUM`: enough observations but not enough evidence for high confidence, or limited reference quality.
- `HIGH`: production reference profile, enough observations, and non-zero runtime variance.

Current threshold:

- `MIN_OBSERVATIONS = 100`

If `observation_count < MIN_OBSERVATIONS`, drift returns `status=UNKNOWN`, `confidence=LOW`, and `reason=insufficient_data`. Non-production reference profiles downgrade confidence even when enough runtime observations exist.

Inference profile lifecycle status:

- `RESET_RECENTLY`: below sample threshold and process uptime is under the reset guardrail.
- `FRESH`: below sample threshold after the reset guardrail.
- `STABLE`: sample threshold reached.

Signals:

- feature mean shift
- feature p95 shift
- missing or invalid feature rate
- score mean shift
- score p95 shift
- high-risk rate shift

Methods are intentionally simple: absolute difference, relative difference, z-score style difference when reference std exists, and threshold flags. Drift detection never blocks scoring and is not fraud detection.

## Drift Actions

FDP-8 starts operationalizing drift without making automated business decisions.

`GET /governance/drift/actions` returns advisory operator guidance:

```json
{
  "severity": "MEDIUM",
  "confidence": "LOW",
  "drift_status": "DRIFT",
  "trend": "INCREASING",
  "recommended_actions": [
    "INVESTIGATE_BASELINE_AND_DATA",
    "CONFIRM_REFERENCE_PROFILE_QUALITY",
    "COMPARE_TRAFFIC_TO_REFERENCE_WINDOW",
    "KEEP_SCORING_UNCHANGED"
  ],
  "escalation": "OPERATOR_REVIEW",
  "automation_policy": {
    "advisory_only": true,
    "affects_scoring": false,
    "blocks_requests": false,
    "switches_model": false,
    "triggers_retraining": false
  },
  "evaluated_at": "2026-04-25T00:00:00+00:00",
  "explanation": "score p95 increased by 18% compared to reference profile",
  "model_lifecycle": {
    "current_model_version": "2026-04-21.trained.v1",
    "model_loaded_at": "2026-04-26T08:00:00+00:00",
    "model_changed_recently": false,
    "recent_lifecycle_event_count": 4
  }
}
```

Recommended action codes include:

- `COLLECT_MORE_DATA`: sample size is too low or profile was reset recently.
- `CHECK_REFERENCE_PROFILE`: reference profile is missing or invalid.
- `CONTINUE_MONITORING`: drift is currently OK.
- `INVESTIGATE_DATA_SHIFT`: WATCH-level signal needs operator review.
- `INVESTIGATE_BASELINE_AND_DATA`: DRIFT exists but confidence is low, commonly due to synthetic or limited reference quality.
- `ESCALATE_MODEL_REVIEW`: higher-confidence DRIFT requires model/data owner review.
- `KEEP_SCORING_UNCHANGED`: explicit guardrail that drift actions are advisory only.

Escalation values:

- `NONE`
- `OPERATOR_REVIEW`
- `MODEL_OWNER_REVIEW`

Trend values are bounded to `STABLE`, `INCREASING`, and `DECREASING`. Trend is computed from the latest bounded snapshot window used by the endpoint, not from an unbounded history scan.

Explanation is deterministic, short, and aggregate-only. It may mention aggregate feature, score, missing-rate, or high-risk-rate movement and the relative or absolute amount changed. It may say that drift was observed after recent model lifecycle activity. It must not claim a lifecycle event caused drift, and it must not include raw feature values, identifiers, request payload fragments, transaction IDs, customer IDs, account IDs, card IDs, user IDs, or correlation IDs.

These are operational recommendations only. They do not block transactions, change risk scores, switch models, retrain models, create analyst alerts, or call external alerting platforms. Actions exist for operator decision-making outside the scoring path.

## Governance Advisory Events

FDP-10 bridges meaningful drift action outcomes into read-only advisory events. An advisory event is an operator signal only. It is not a fraud alert, not a model action, not a retraining trigger, not a rollback trigger, and not an automated decision.

Events are emitted only when drift actions are operationally meaningful:

- severity is `HIGH` or `CRITICAL`
- or escalation is not `NONE`
- and confidence is not `LOW`

Events are not emitted for `LOW`, `INFO`, or low-confidence actions. This keeps routine local/synthetic drift output from becoming operational noise.

Advisory events are heuristic signals and may be inaccurate under low data conditions. The system does not guarantee correctness of drift or advisory signals. Operators must treat advisories as review context, not proof of model or data failure.

Advisory events are deduplicated to avoid repeated signals. The fingerprint is `model_name`, `model_version`, `severity`, and `drift_status`; an identical advisory emitted within the 5-minute deduplication window is skipped. Deduplication is best-effort and works against both persisted MongoDB history and bounded in-memory fallback history.

Event schema:

```json
{
  "event_id": "uuid",
  "event_type": "GOVERNANCE_DRIFT_ADVISORY",
  "severity": "HIGH",
  "drift_status": "DRIFT",
  "confidence": "HIGH",
  "advisory_confidence_context": "SUFFICIENT_DATA",
  "model_name": "python-logistic-fraud-model",
  "model_version": "2026-04-21.trained.v1",
  "lifecycle_context": {
    "current_model_version": "2026-04-21.trained.v1",
    "model_loaded_at": "2026-04-26T08:00:00+00:00",
    "model_changed_recently": false,
    "recent_lifecycle_event_count": 4
  },
  "recommended_actions": [
    "ESCALATE_MODEL_REVIEW",
    "OPEN_MODEL_DATA_REVIEW",
    "KEEP_SCORING_UNCHANGED"
  ],
  "explanation": "score p95 increased by 18% compared to reference profile",
  "created_at": "2026-04-26T08:05:00+00:00"
}
```

Advisory confidence context:

- `LOW_SAMPLE`: inference sample volume is below the current drift guardrail.
- `PARTIAL_DATA`: reference or inference profile context is incomplete, unavailable, or freshly reset.
- `STABLE_BASELINE`: the signal has stable baseline context but does not meet the strongest reliability criteria.
- `SUFFICIENT_DATA`: the signal has enough aggregate runtime context for higher-confidence operator review.

The confidence context is a bounded enum. It does not expose sample counts, raw feature values, payload data, or statistical internals.

Persistence:

```text
GOVERNANCE_ADVISORY_COLLECTION=ml_governance_advisory_events
GOVERNANCE_ADVISORY_RETENTION_LIMIT=200
```

MongoDB persistence is optional and best-effort. If MongoDB is unavailable, advisory events fall back to bounded process memory. Persistence failure does not break scoring, drift evaluation, drift actions, or the read-only advisory API.

Indexes:

- `created_at` descending
- `severity`
- `model_name`, `model_version`

Read-only API:

```text
GET /governance/advisories
GET /governance/advisories?limit=25
GET /governance/advisories?severity=HIGH
GET /governance/advisories?model_version=2026-04-21.trained.v1
```

The response is newest-first, bounded to a maximum of 100 events, and returns `status=AVAILABLE`, `PARTIAL`, or `UNAVAILABLE`. Filters are exact bounded filters only: `severity`, `model_version`, and `limit`. There is no free-text search, regex, dynamic query language, or identifier lookup.

Advisory events contain aggregate governance context only. They exclude user IDs, transaction IDs, correlation IDs, raw feature values, request payloads, artifact contents, credentials, raw exception text, and unbounded arrays. Lifecycle context remains bounded and must not be interpreted as causality; drift may be observed after lifecycle activity, but this runtime does not claim lifecycle activity caused drift.

FDP-12 adds an analyst console operator queue for this endpoint. FDP-13 adds a minimal human-review audit trail for advisory events through the authenticated `alert-service` boundary. FDP-14 adds advisory lifecycle visibility derived from that audit trail. The queue still does not create fraud alerts, submit fraud decisions, change scoring, trigger retraining, or trigger rollback.

## Governance Advisory Audit Trail

FDP-13 records append-only operator review entries for governance advisory events. This audit trail records human review only. It does not mutate the advisory event, does not create a fraud alert, does not make a business decision, does not change scoring, does not switch models, and does not trigger retraining or rollback.

Write boundary:

```text
POST /governance/advisories/{event_id}/audit
GET /governance/advisories/{event_id}/audit
```

These endpoints are owned by `alert-service`, not `ml-inference-service`, because audit writes require an authenticated operator actor. The ML service remains provider-neutral and unauthenticated in local runtime. The frontend sends only `decision` and optional `note`; `actor_id`, roles, and display name are derived from the backend-authenticated principal/session.

Allowed decisions:

- `ACKNOWLEDGED`
- `NEEDS_FOLLOW_UP`
- `DISMISSED_AS_NOISE`

Audit event schema:

```json
{
  "audit_id": "uuid",
  "advisory_event_id": "uuid",
  "decision": "ACKNOWLEDGED",
  "note": "Reviewed by operator",
  "actor_id": "analyst-1",
  "actor_display_name": "analyst-1",
  "actor_roles": ["ANALYST"],
  "created_at": "2026-04-26T10:00:00Z",
  "model_name": "python-logistic-fraud-model",
  "model_version": "2026-04-21.trained.v1",
  "advisory_severity": "HIGH",
  "advisory_confidence": "HIGH",
  "advisory_confidence_context": "SUFFICIENT_DATA"
}
```

Validation and privacy:

- `note` is optional and capped at 500 characters.
- frontend-provided actor fields are rejected.
- raw advisory payloads, transaction identifiers, customer identifiers, model artifacts, secrets, and raw feature values are not persisted.
- audit writes fail clearly if persistence or advisory lookup is unavailable; they are not silently dropped.
- audit history returns newest-first bounded results and may return `status=UNAVAILABLE` when storage is unavailable.

Persistence:

```text
collection: ml_governance_audit_events
history reads: bounded by GOVERNANCE_AUDIT_HISTORY_LIMIT, default 50 newest events
analytics reads: bounded by GOVERNANCE_AUDIT_ANALYTICS_MAX_AUDIT_EVENTS, default 10000 events
```

Indexes:

- `advisory_event_id`, `created_at` descending
- `actor_id`, `created_at` descending
- `model_name`, `model_version`, `created_at` descending

### Advisory Lifecycle Status

FDP-14 exposes `lifecycle_status` on governance advisory reads through `alert-service`:

```text
GET /governance/advisories
GET /governance/advisories/{event_id}
```

Lifecycle status is a derived projection from audit history:

- `OPEN`: no audit events exist.
- `ACKNOWLEDGED`: latest audit decision is `ACKNOWLEDGED`.
- `NEEDS_FOLLOW_UP`: latest audit decision is `NEEDS_FOLLOW_UP`.
- `DISMISSED_AS_NOISE`: latest audit decision is `DISMISSED_AS_NOISE`.

Only the latest audit event is considered. The audit trail remains the source of truth. Lifecycle status is computed at read time, is not stored as authoritative state, and is not a workflow engine.

Filtering by `lifecycle_status` applies to the bounded advisory result set. It does not guarantee global completeness.

## Lifecycle Red Lines

**Lifecycle is a read-only projection of audit history.**

Lifecycle status:

- is NOT a workflow engine
- does NOT trigger actions
- does NOT influence scoring
- does NOT influence model behavior
- is NOT persisted as authoritative state

Lifecycle limitations:

- no automation
- no SLA
- no transition graph
- no background jobs, timers, or cron
- no retraining, rollback, alerting, scoring, or decision side effects

## Audit Analytics

FDP-15 adds bounded audit analytics through `alert-service`:

```text
GET /governance/advisories/analytics?window_days=7
```

Analytics are derived from advisory history and human-review audit events. They are read-only and are for visibility into operator behavior and advisory handling only.

Definitions:

- `advisory_event_id`: unique identifier of an advisory from the ML governance system.
- `totals.advisories`: number of distinct `advisory_event_id` values in the bounded advisory projection window.
- `totals.reviewed`: advisories in that same projection window with at least one matching audit event.
- `totals.open`: advisories in that same projection window with zero matching audit events.
- `decision_distribution`: latest audit decision distribution for reviewed advisories in that same projection window.
- `lifecycle_distribution`: lifecycle distribution after read-time lifecycle enrichment of that same advisory projection; counts sum to `totals.advisories`.

Audit-only advisory IDs outside the bounded advisory projection are not counted in totals or distributions.

Returned analytics include:

- total advisories, reviewed advisories, and open advisories
- latest audit decision distribution
- read-time lifecycle distribution
- time-to-first-review p50 and p95 in minutes

Time-to-first-review is computed as `first_audit.created_at - advisory.created_at`. Only advisories with at least one valid, non-negative audit duration are sampled. If fewer than five valid samples exist, `review_timeliness.status` is `LOW_CONFIDENCE` and percentile values are not presented as reliable operational statistics.

Analytics status:

- `AVAILABLE`: advisory source and audit source are both readable.
- `PARTIAL`: one source is degraded, or the bounded audit scan limit is exceeded.
- `UNAVAILABLE`: both sources are unavailable or a critical dependency is missing.

When analytics are `PARTIAL` or `UNAVAILABLE`, the optional `reason` field explains the safe bounded degradation without exposing sensitive internals:

- `AUDIT_LIMIT_EXCEEDED`
- `AUDIT_UNAVAILABLE`
- `ADVISORY_UNAVAILABLE`

Analytics are not an SLA, do not trigger actions, do not persist aggregates, do not change lifecycle state, and do not influence scoring, model behavior, retraining, rollback, alerts, or fraud decisions.

Analytics operates on bounded time windows. `window_days` defaults to `7` and is capped at `30`, the advisory input remains bounded to the recent advisory window, and audit scans are capped by `GOVERNANCE_AUDIT_ANALYTICS_MAX_AUDIT_EVENTS` (default `10000`). Audit queries use `created_at`; lifecycle lookups use `advisory_event_id` with `created_at`.

### Analytics Red Lines

Analytics:

- is NOT for alert triggering
- is NOT for SLA enforcement
- is NOT for model control
- is NOT for automation
- does NOT influence scoring, retraining, rollback, or fraud decisions

### Analytics Metrics Semantics

Analytics metrics represent observational distributions and endpoint health only. They are NOT SLA signals, NOT alerts, and NOT triggers. Metrics must not be used for automated decisions.

## Endpoint Contracts

```text
GET /governance/model
GET /governance/model/current
GET /governance/model/lifecycle
GET /governance/profile/reference
GET /governance/profile/inference
GET /governance/drift
GET /governance/drift/actions
GET /governance/advisories
GET /governance/history
GET /governance/advisories/{event_id}
POST /governance/advisories/{event_id}/audit
GET /governance/advisories/{event_id}/audit
```

These endpoints are additive. Existing endpoints remain compatible:

```text
POST /v1/fraud/score
GET /health
GET /metrics
```

The current ML API surface and backward-compatibility rules are tracked in `docs/api-surface-v1.md`; the OpenAPI reference is `docs/openapi/ml-inference-service.openapi.yaml`. Governance and scoring success responses keep their existing top-level fields. Error responses use the platform `timestamp/status/error/message/details` envelope.

No auth is added to the ML governance read endpoints because `ml-inference-service` does not currently own an auth boundary. Governance audit writes are different: they are handled by `alert-service` so the authenticated analyst principal remains the source of actor attribution. In production, expose ML read endpoints only through the same network controls used for operational metrics.

History response:

```json
{
  "status": "AVAILABLE",
  "count": 1,
  "oldestTimestamp": "2026-04-25T00:00:00+00:00",
  "newestTimestamp": "2026-04-25T00:00:00+00:00",
  "snapshots": []
}
```

`GET /governance/history` supports `limit`, capped at `100`. If MongoDB is unavailable, the endpoint returns `status=UNAVAILABLE` and a single bounded current in-memory snapshot instead of leaking internal errors.

## Metrics

Governance metrics:

- `fraud_ml_governance_drift_status{model_name,model_version,status}`
- `fraud_ml_governance_drift_confidence{model_name,model_version,confidence}`
- `fraud_ml_governance_drift_action_recommendation{model_name,model_version,severity}`
- `fraud_ml_governance_feature_drift_detected{model_name,model_version,severity}`
- `fraud_ml_governance_score_drift_detected{model_name,model_version,severity}`
- `fraud_ml_governance_profile_observations_total{model_name,model_version}`
- `fraud_ml_governance_reference_profile_loaded{model_name,model_version,status}`
- `fraud_ml_governance_snapshots_persisted_total{model_name,model_version,status}`
- `fraud_ml_governance_snapshot_persistence_failures_total{model_name,model_version,status}`
- `fraud_ml_governance_snapshot_history_available{model_name,model_version,status}`
- `fraud_ml_model_lifecycle_info{model_name,model_version,lifecycle_mode}`
- `fraud_ml_model_lifecycle_events_total{event_type,model_name,model_version,status}`
- `fraud_ml_model_lifecycle_history_available{model_name,model_version,status}`
- `fraud_ml_governance_advisory_lifecycle_total{lifecycle_status,model_name,model_version}`
- `fraud_ml_governance_analytics_requests_total`
- `fraud_ml_governance_analytics_window_days`
- `fraud_ml_governance_advisory_events_emitted_total{severity,model_name,model_version,status}`
- `fraud_ml_governance_advisory_events_persisted_total{severity,model_name,model_version,status}`
- `fraud_ml_governance_advisory_persistence_failures_total{severity,model_name,model_version,status}`

Allowed labels are bounded to model metadata and status/severity. Feature-level details stay in JSON endpoints to avoid high-cardinality Prometheus series.

Forbidden metric labels:

- `transactionId`
- `customerId`
- `accountId`
- `cardId`
- `userId`
- `correlationId`
- raw feature values
- sample size
- snapshot ID
- collection name
- exception message
- reason text
 
Action metric labels are bounded enums only. They must never include action text, escalation text, explanation text, feature names, raw drift reasons, snapshot IDs, user identifiers, or exception messages.

Lifecycle metric labels are bounded to model metadata, lifecycle mode, fixed lifecycle event types, advisory lifecycle status, and status. They must never include artifact paths, checksums, event IDs, actor IDs, timestamps, reason text, exception text, hostnames, or filesystem paths.

Lifecycle metrics represent distribution of advisory states, not operational decisions.

Analytics metrics represent observational distribution of advisory states. They do NOT represent system decisions or actions. Analytics metrics have no user, actor, event, advisory, or payload labels.

Advisory metric labels are bounded to severity, model name, model version, and status. They must never include event IDs, timestamps, explanations, recommended action text, payload data, feature names, exception text, or identifiers.

## Privacy Policy

Governance data is aggregate-only. It must not persist customer-level records or raw payloads. The reference profile is synthetic/local and aggregate. The inference profile stores numeric aggregates and fixed histogram buckets, not per-request rows. MongoDB snapshots persist summaries only and are safe to inspect operationally.

## Incident Playbook

For `UNKNOWN`:

1. Check whether the reference profile is loaded.
2. Check whether runtime observations are below the minimum sample guardrail.
3. Confirm the active model version matches the reference profile.
4. Check `inference_profile_status` to distinguish a recent restart from a low-traffic profile.

For history `UNAVAILABLE`:

1. Confirm `MONGODB_URI` and local MongoDB container state.
2. Check `fraud_ml_governance_snapshot_persistence_failures_total`.
3. Verify scoring still works; MongoDB persistence is not on the scoring correctness path.
4. Restore MongoDB availability to resume persisted history.

For lifecycle history `PARTIAL` or `UNAVAILABLE`:

1. Check `MODEL_LIFECYCLE_COLLECTION` and MongoDB availability.
2. Check `fraud_ml_model_lifecycle_history_available`.
3. Use the returned in-memory lifecycle events for immediate triage.
4. Verify scoring still works; lifecycle persistence is not on the scoring correctness path.

For `WATCH`:

1. Inspect `/governance/drift` signal details.
2. Inspect `/governance/drift/actions` for recommended operator steps.
3. Compare feature drift and score drift separately.
4. Check recent deploys, feature contract changes, traffic mix changes, and simulator/replay source changes.
5. Continue monitoring; do not change scoring thresholds solely because of `WATCH`.

For `DRIFT`:

1. Confirm the signal is not caused by low traffic or a local synthetic workload mismatch.
2. Inspect `/governance/drift/actions` to distinguish baseline/data investigation from model-owner escalation.
3. Verify model version and feature contract version.
4. Inspect `/governance/model/current` and `/governance/model/lifecycle` for read-only lifecycle context.
5. Inspect Java fallback and ML runtime health metrics to separate data drift from service failure.
6. Escalate for model review or retraining analysis outside this runtime path.
7. Do not rely on drift detection as a fraud decision or automatic rollback trigger.

For advisory events:

1. Inspect `GET /governance/advisories?limit=25`.
2. Treat the event as operator context, not a fraud alert or model action.
3. Use recommended actions to guide manual review outside the scoring path.
4. Check `advisory_confidence_context`; low-sample and partial-data advisories can be misleading.
5. Do not infer that lifecycle activity caused drift.
6. Confirm scoring behavior remains unchanged.
7. If human review is needed, record an audit entry from the analyst console; the entry is review history only.

For advisory audit persistence `UNAVAILABLE`:

1. Confirm MongoDB availability for `alert-service`.
2. Check whether `GET /governance/advisories/{event_id}/audit` returns `status=UNAVAILABLE`.
3. Retry the audit write only after persistence is available; write intent is never silently dropped.
4. Confirm scoring and ML drift endpoints remain unchanged.

## Limitations

- The reference profile is synthetic/local, not production traffic.
- Runtime inference profile is in-memory and resets on process restart.
- Persisted governance history survives `ml-inference-service` restart when MongoDB is available.
- MongoDB outage pauses persisted history but does not break scoring.
- Lifecycle history falls back to bounded process memory when MongoDB is unavailable.
- Synthetic reference confidence is intentionally capped at `LOW`.
- Quantiles for inference are histogram approximations.
- No automatic retraining, rollback, approval workflow, feature store, or alert routing is implemented.
- Drift actions are advisory and do not create tickets, notify PagerDuty, mutate scoring, or trigger workflows.
- No model quality monitoring is claimed; FDP-7 monitors input and output distribution shift only.
- FDP-9 adds lifecycle visibility only; it does not implement lifecycle control or model quality validation.
- FDP-13 audit trail records human review only; it does not implement advisory status transitions, update/delete operations, fraud alerts, model automation, retraining triggers, or rollback triggers.
- Drift thresholds are starting guardrails and need calibration against real baselines.

## Out Of Scope

- automatic model rollback
- automated retraining
- human approval UI
- online feature store
- production alert routing
- blocking live scoring based on drift
- Java fallback behavior changes
- full MLOps tooling such as MLflow, Feast, Airflow, Spark, or cloud MLOps services
