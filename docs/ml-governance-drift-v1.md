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
- `GET /governance/profile/reference` exposes the loaded aggregate reference profile.
- `GET /governance/profile/inference` exposes process-local aggregate inference stats.
- `GET /governance/drift` compares inference stats against the reference profile and returns status, confidence, sample size, lifecycle status, and bounded signal details.
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

## Endpoint Contracts

```text
GET /governance/model
GET /governance/profile/reference
GET /governance/profile/inference
GET /governance/drift
```

These endpoints are additive. Existing endpoints remain compatible:

```text
POST /v1/fraud/score
GET /health
GET /metrics
```

No auth is added in FDP-7 because `ml-inference-service` does not currently own an auth boundary. In production, expose these endpoints only through the same network controls used for operational metrics.

## Metrics

Governance metrics:

- `fraud_ml_governance_drift_status{model_name,model_version,status}`
- `fraud_ml_governance_drift_confidence{model_name,model_version,confidence}`
- `fraud_ml_governance_feature_drift_detected{model_name,model_version,severity}`
- `fraud_ml_governance_score_drift_detected{model_name,model_version,severity}`
- `fraud_ml_governance_profile_observations_total{model_name,model_version}`
- `fraud_ml_governance_reference_profile_loaded{model_name,model_version,status}`

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
- reason text

## Privacy Policy

Governance data is aggregate-only. It must not persist customer-level records or raw payloads. The reference profile is synthetic/local and aggregate. The inference profile stores numeric aggregates and fixed histogram buckets, not per-request rows.

## Incident Playbook

For `UNKNOWN`:

1. Check whether the reference profile is loaded.
2. Check whether runtime observations are below the minimum sample guardrail.
3. Confirm the active model version matches the reference profile.
4. Check `inference_profile_status` to distinguish a recent restart from a low-traffic profile.

For `WATCH`:

1. Inspect `/governance/drift` signal details.
2. Compare feature drift and score drift separately.
3. Check recent deploys, feature contract changes, traffic mix changes, and simulator/replay source changes.
4. Continue monitoring; do not change scoring thresholds solely because of `WATCH`.

For `DRIFT`:

1. Confirm the signal is not caused by low traffic or a local synthetic workload mismatch.
2. Verify model version and feature contract version.
3. Inspect Java fallback and ML runtime health metrics to separate data drift from service failure.
4. Escalate for model review or retraining analysis outside this runtime path.
5. Do not rely on drift detection as a fraud decision or automatic rollback trigger.

## Limitations

- The reference profile is synthetic/local, not production traffic.
- Runtime inference profile is in-memory and resets on process restart.
- Synthetic reference confidence is intentionally capped at `LOW`.
- Quantiles for inference are histogram approximations.
- No automatic retraining, rollback, approval workflow, feature store, or alert routing is implemented.
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
