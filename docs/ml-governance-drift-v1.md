# ML Governance And Drift v1

FDP-7 adds the first bounded ML governance layer for `ml-inference-service`.

This is runtime oversight, not a full MLOps platform. It exposes model lineage, aggregate reference and inference profiles, simple threshold drift checks, and low-cardinality Prometheus signals. It does not change scoring, alert thresholds, fraud decisions, Java fallback behavior, or model training.

## Purpose

Operators need to answer:

- which model version is active
- what baseline distribution the runtime is compared against
- what aggregate distribution live inference is seeing
- whether input features or score outputs have shifted
- what to inspect when drift appears

## Current Capabilities

- `GET /governance/model` exposes active model metadata and artifact lineage.
- `GET /governance/model/current` exposes read-only current model lifecycle metadata.
- `GET /governance/model/lifecycle` exposes bounded read-only model lifecycle events.
- `GET /governance/profile/reference` exposes the loaded aggregate reference profile.
- `GET /governance/profile/inference` exposes process-local aggregate inference stats.
- `GET /governance/drift` compares inference stats against the reference profile and returns status, confidence, sample size, lifecycle status, and bounded signal details.
- `GET /governance/drift/actions` interprets drift into advisory operator actions and escalation guidance.
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

FDP-9 adds read-only lifecycle visibility. It provides operational context for drift investigation, but it is not lifecycle control.

It does not switch models, retrain models, rollback models, approve models, validate model quality, change scoring behavior, or change Java fallback behavior.

`GET /governance/model/current` returns current model lifecycle metadata:

```json
{
  "model_lifecycle": {
    "model_name": "python-logistic-fraud-model",
    "model_version": "2026-04-21.trained.v1",
    "model_family": "LOGISTIC_REGRESSION",
    "loaded_at": "2026-04-25T00:00:00+00:00",
    "artifact_path_or_id": "model_artifact.json",
    "artifact_source": "synthetic-fraud-scenarios",
    "artifact_checksum": "sha256",
    "feature_set_version": null,
    "training_mode": "production",
    "reference_profile_id": "2026-04-25.synthetic.v1",
    "runtime_environment": {
      "service": "ml-inference-service",
      "runtime": "python"
    },
    "lifecycle_mode": "READ_ONLY"
  }
}
```

The endpoint does not expose raw model artifact contents, weights, thresholds, filesystem secrets, credentials, or host-specific secret paths. `artifact_checksum` is metadata only.

`GET /governance/model/lifecycle` returns current metadata plus bounded lifecycle events:

```json
{
  "status": "AVAILABLE",
  "count": 1,
  "model_lifecycle": {},
  "events": [
    {
      "eventId": "uuid",
      "eventType": "MODEL_LOADED",
      "occurredAt": "2026-04-25T00:00:00+00:00",
      "modelName": "python-logistic-fraud-model",
      "modelVersion": "2026-04-21.trained.v1",
      "previousModelVersion": null,
      "source": "ml-inference-service",
      "reason": "runtime_startup",
      "metadataSummary": {
        "lifecycle_mode": "READ_ONLY"
      }
    }
  ]
}
```

Lifecycle event types are `MODEL_LOADED`, `MODEL_METADATA_DETECTED`, `REFERENCE_PROFILE_LOADED`, `GOVERNANCE_HISTORY_AVAILABLE`, and `GOVERNANCE_HISTORY_UNAVAILABLE`.

Lifecycle history status:

- `AVAILABLE`: MongoDB lifecycle history is available.
- `PARTIAL`: MongoDB lifecycle history is unavailable, so the endpoint returns bounded in-memory events.
- `UNAVAILABLE`: neither persisted nor in-memory lifecycle events are available.

Lifecycle persistence:

- Collection: `ml_model_lifecycle_events`
- Config: `MODEL_LIFECYCLE_COLLECTION`, default `ml_model_lifecycle_events`
- Config: `MODEL_LIFECYCLE_RETENTION_LIMIT`, default `200`
- Indexes: `occurredAt` descending and `modelName`, `modelVersion`, `occurredAt` descending
- Retention is per `modelName` and `modelVersion`.
- MongoDB write, index, or retention failure does not affect scoring.

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
    "model_loaded_at": "2026-04-25T00:00:00+00:00",
    "model_loaded_recently": true,
    "recent_lifecycle_event_count": 3
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

Explanation is deterministic, short, and aggregate-only. It may mention aggregate feature, score, missing-rate, or high-risk-rate movement and the relative or absolute amount changed. It must not include raw feature values, identifiers, request payload fragments, transaction IDs, customer IDs, account IDs, card IDs, user IDs, or correlation IDs.

These are operational recommendations only. They do not block transactions, change risk scores, switch models, retrain models, create analyst alerts, or call external alerting platforms. Actions exist for operator decision-making outside the scoring path.

`model_loaded_recently` means the model runtime was initialized recently, based on process load time. It does not imply that a new model version was deployed, that a model change occurred, or that drift is caused by model updates.

Lifecycle signals are observational and must not be interpreted as causal drivers of drift. Drift/lifecycle correlation is context only. The system may show that drift was observed after recent runtime lifecycle activity, but it must not claim that a lifecycle event caused drift.

## Endpoint Contracts

```text
GET /governance/model
GET /governance/model/current
GET /governance/model/lifecycle
GET /governance/profile/reference
GET /governance/profile/inference
GET /governance/drift
GET /governance/drift/actions
GET /governance/history
```

These endpoints are additive. Existing endpoints remain compatible:

```text
POST /v1/fraud/score
GET /health
GET /metrics
```

No auth is added in FDP-7 because `ml-inference-service` does not currently own an auth boundary. In production, expose these endpoints only through the same network controls used for operational metrics.

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

Allowed labels are bounded to model metadata, status/severity, lifecycle mode, and lifecycle event type. Feature-level details stay in JSON endpoints to avoid high-cardinality Prometheus series.

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
- artifact path
- checksum
- event ID
- timestamp
 
Action metric labels are bounded enums only. They must never include action text, escalation text, explanation text, feature names, raw drift reasons, snapshot IDs, user identifiers, or exception messages.

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

For `WATCH`:

1. Inspect `/governance/drift` signal details.
2. Inspect `/governance/drift/actions` for recommended operator steps.
3. Compare feature drift and score drift separately.
4. Check recent deploys, feature contract changes, traffic mix changes, and simulator/replay source changes.
5. Continue monitoring; do not change scoring thresholds solely because of `WATCH`.

For `DRIFT`:

1. Confirm the signal is not caused by low traffic or a local synthetic workload mismatch.
2. Inspect `/governance/drift/actions` to distinguish baseline/data investigation from model-owner escalation.
3. Inspect `/governance/model/lifecycle` for recent read-only lifecycle events.
4. Verify model version and feature contract version.
5. Inspect Java fallback and ML runtime health metrics to separate data drift from service failure.
6. Escalate for model review or retraining analysis outside this runtime path.
7. Do not rely on drift detection as a fraud decision or automatic rollback trigger.

## Limitations

- The reference profile is synthetic/local, not production traffic.
- Runtime inference profile is in-memory and resets on process restart.
- Persisted governance history survives `ml-inference-service` restart when MongoDB is available.
- Persisted lifecycle history survives `ml-inference-service` restart when MongoDB is available.
- MongoDB outage pauses persisted history but does not break scoring.
- Synthetic reference confidence is intentionally capped at `LOW`.
- Quantiles for inference are histogram approximations.
- No automatic retraining, rollback, approval workflow, feature store, or alert routing is implemented.
- Drift actions are advisory and do not create tickets, notify PagerDuty, mutate scoring, or trigger workflows.
- Model lifecycle visibility is read-only and does not implement lifecycle control.
- No model quality monitoring is claimed; FDP-7 monitors input and output distribution shift only.
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
