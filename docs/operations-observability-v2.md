# Operations And Observability v2

Reviewer-friendly operations spec for `fraud-detection-platform` after FDP-5.

This document supersedes `operations-observability-v1.md` for current local runtime decisions.

This document extends the current observability foundation with direct ML runtime metrics so that `ml-inference-service` can be monitored as an independent operational component.

## Scope

v2 covers:

- Micrometer metrics in `alert-service`
- Micrometer metrics in `fraud-scoring-service`
- direct Prometheus-style metrics in `ml-inference-service`
- structured logs across services
- correlation and trace context propagation through HTTP and Kafka boundaries
- operator guidance for ML runtime incidents

v2 still does not cover:

- a production monitoring stack rollout
- distributed tracing export
- ML quality, drift, or feature-distribution monitoring
- automatic alert routing configuration in repo

## Local Runtime Stack

FDP-5 now includes a local-only monitoring stack in `deployment`:

- Prometheus on `http://localhost:9090`
- Grafana on `http://localhost:3000`
- auto-provisioned Prometheus datasource
- auto-provisioned dashboard: `FDP-5 ML Observability`
- Prometheus alert rules for latency, inference errors, model load failure, and scoring fallback spike

Recommended startup for observability validation:

```bash
docker compose -f deployment/docker-compose.yml up --build -d \
  kafka kafka-topics-init mongodb redis \
  ml-inference-service feature-enricher-service transaction-simulator-service fraud-scoring-service \
  prometheus grafana
```

Full OIDC + observability startup:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml up --build -d
```

Fast restart when images are already built locally:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml up -d --no-build
```

Prometheus scrape targets:

- `ml-inference-service:8090/metrics`
- `fraud-scoring-service:8080/actuator/prometheus`
- `alert-service:8080/actuator/prometheus`

## ML Service Metrics

`ml-inference-service` now exposes:

- `GET /metrics`
- `GET /health`
- `POST /v1/fraud/score`

Prometheus metric contract:

- `fraud_ml_inference_requests_total`
  - Type: counter
  - Meaning: total HTTP requests handled by the ML runtime
  - Labels: `endpoint`, `method`, `status`, `outcome`
- `fraud_ml_inference_request_latency_seconds`
  - Type: histogram
  - Meaning: HTTP request latency at the ML runtime boundary
  - Labels: `endpoint`, `method`, `status`, `outcome`
- `fraud_ml_inference_errors_total`
  - Type: counter
  - Meaning: rejected or failed inference requests
  - Labels: `endpoint`, `method`, `outcome`
- `fraud_ml_model_load_status`
  - Type: gauge
  - Meaning: current runtime model load status
  - Labels: `outcome`, `model_name`, `model_version`
- `fraud_ml_model_info`
  - Type: gauge
  - Meaning: active model metadata for the running runtime
  - Labels: `model_name`, `model_version`

## Low-Cardinality Policy

ML runtime metrics must describe runtime behavior, not business payloads.

Allowed bounded labels:

- `endpoint`
- `method`
- `status`
- `outcome`
- `model_name`
- `model_version`

Forbidden labels and payload-derived fields:

- `transactionId`
- `customerId`
- `userId`
- `alertId`
- `correlationId`
- raw exception messages
- request feature names and values
- merchant, customer, device, or country identifiers

Use logs for sample-level investigation. Use metrics for bounded aggregation only.

## Dashboard Queries

These queries are the exact panel/query basis for the shipped dashboard and Prometheus alert rules.

- Request rate
```promql
sum by (outcome) (
  rate(fraud_ml_inference_requests_total{endpoint="/v1/fraud/score",method="POST"}[5m])
)
```

- Error rate
```promql
sum(rate(fraud_ml_inference_errors_total{endpoint="/v1/fraud/score",outcome="inference_error"}[5m]))
/
clamp_min(sum(rate(fraud_ml_inference_requests_total{endpoint="/v1/fraud/score",method="POST"}[5m])), 0.001)
```

- p95 latency for scoring
```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(fraud_ml_inference_request_latency_seconds_bucket{endpoint="/v1/fraud/score",method="POST",status="200",outcome="success"}[5m])
  )
)
```

- Model load status
```promql
max(fraud_ml_model_load_status{outcome="success"})
```

- Active model info
```promql
fraud_ml_model_info
```

- Scoring fallback ratio in Java
```promql
sum(rate(fraud_scoring_fallbacks_total[5m]))
/
clamp_min(sum(rate(fraud_scoring_requests_total[5m])), 0.001)
```

## Alert Thresholds

These thresholds are starting points for a local or near-production synthetic workload. They must be tuned after real traffic baselining.

| Signal | Query | Warning threshold | Critical threshold | Action |
| --- | --- | --- | --- | --- |
| ML service availability | `sum(rate(fraud_ml_inference_requests_total{endpoint="/v1/fraud/score",status="200"}[5m])) / sum(rate(fraud_ml_inference_requests_total{endpoint="/v1/fraud/score"}[5m]))` | `< 0.99 for 10m` | `< 0.95 for 5m` | Check ML container health, then inspect Java fallback rate and recent deploy/config changes. |
| ML inference error rate | `sum(rate(fraud_ml_inference_errors_total{outcome="inference_error"}[5m])) / sum(rate(fraud_ml_inference_requests_total{endpoint="/v1/fraud/score"}[5m]))` | `> 0.01 for 10m` | `> 0.05 for 5m` | Inspect ML service logs for model/runtime failures and verify artifact integrity. |
| ML p95 latency | `histogram_quantile(0.95, sum by (le) (rate(fraud_ml_inference_request_latency_seconds_bucket{endpoint="/v1/fraud/score",method="POST"}[5m])))` | `> 0.25s for 10m` | `> 1.0s for 5m` | Check CPU or saturation in the ML container and whether Java client latency/fallbacks are rising too. |
| Request volume anomaly | `sum(rate(fraud_ml_inference_requests_total{endpoint="/v1/fraud/score"}[15m]))` | `< 50% of trailing 24h baseline` | `< 20% of trailing 24h baseline` | Determine whether traffic loss is upstream pipeline loss or ML endpoint isolation. |
| Model load failure | `fraud_ml_model_load_status{outcome="failure"}` | `> 0 for 5m` | `> 0 for 1m` | Treat as runtime misconfiguration or bad artifact; verify image, mounted artifact, and startup logs. |
| Fraud-scoring fallback spike | `sum(rate(fraud_scoring_fallbacks_total[5m])) / clamp_min(sum(rate(fraud_scoring_requests_total[5m])), 0.001)` | `> 0.02 for 10m` | `> 0.10 for 5m` | Use Java fallback telemetry and ML `/metrics` together to distinguish ML outage from semantic unavailability. |

## Runtime Validation Checklist

Expected end-to-end checks after startup:

1. Prometheus target page shows `ml-inference-service` and `fraud-scoring-service` as `UP`.
2. Grafana loads the `FDP-5 ML Observability` dashboard without manual import.
3. Calling `POST /v1/fraud/score` changes ML request rate and latency panels.
4. Sending malformed requests increments the ML error/rejected signal.
5. Java fallback ratio becomes visible when scoring continues while ML becomes unavailable.

Useful runtime checks:

```bash
curl http://localhost:9090/api/v1/targets
curl http://localhost:9090/api/v1/rules
curl http://localhost:8090/metrics
```

If `ml-inference-service` is `DOWN` in Prometheus and `/metrics` returns `404`, the running Docker image is older than the current repo code. Rebuild the ML image:

```bash
docker compose -f deployment/docker-compose.yml build ml-inference-service
docker compose -f deployment/docker-compose.yml up -d ml-inference-service prometheus grafana
```

If the OIDC frontend or callback behavior is stale after auth changes, rebuild the frontend image too:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml build analyst-console-ui
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml up -d analyst-console-ui
```

## Incident Scenarios

### 1. ML Service Unavailable

What breaks:
- Java scoring falls back more often to rule-based scoring.
- Shadow and compare visibility may remain partial, but direct ML runtime behavior is unavailable.

How to detect it:
- `fraud_scoring_ml_client_requests_total{outcome="error"}` rises.
- `fraud_scoring_fallbacks_total` rises.
- `fraud_ml_inference_requests_total` for scoring drops sharply or stops.

Likely causes:
- container crash
- port binding or network issue
- startup failure after image or artifact change

Immediate response:
- verify `/health` and container status
- check recent deployment or artifact changes
- confirm Java fallback is keeping scoring alive

Follow-up action:
- restore ML service availability
- verify model artifact packaging
- review whether availability alert thresholds need tuning

### 2. ML Latency Degradation

What breaks:
- scoring remains available but slower
- Java client latency can rise before hard failures appear

How to detect it:
- ML p95 latency query breaches threshold
- `fraud_scoring_ml_client_latency_seconds` rises in Java
- fallbacks may remain flat early in the incident

Likely causes:
- CPU starvation
- noisy-neighbor container contention
- oversized runtime workload

Immediate response:
- inspect host or container resource pressure
- compare ML runtime latency with Java client latency
- check whether degradation started after a model or image change

Follow-up action:
- rebaseline latency after remediation
- consider capacity or artifact-size review

### 3. Rising Inference Errors

What breaks:
- scoring calls reach ML but inference fails
- fallback usage can mask impact on end-user flow

How to detect it:
- `fraud_ml_inference_errors_total{outcome="inference_error"}` rises
- `fraud_scoring_fallbacks_total` rises
- Java client may still show request success if ML endpoint responds but returns unusable output

Likely causes:
- bad model artifact
- runtime incompatibility
- malformed internal runtime state

Immediate response:
- inspect ML logs around `score_failed`
- verify the active `fraud_ml_model_info` version
- roll back the artifact or image if failure started after a rollout

Follow-up action:
- add artifact validation to release flow if needed
- compare failing version against previous known-good version

### 4. Unexpected Model Version

What breaks:
- scoring may still work, but operators lose confidence that the intended model is running

How to detect it:
- `fraud_ml_model_info` shows an unexpected `model_version`
- Java diagnostics and ML runtime metadata disagree on expected version

Likely causes:
- wrong artifact bundled into image
- stale registry or champion selection mismatch
- manual local override left active

Immediate response:
- verify deployed artifact and runtime metadata
- compare expected release manifest with `fraud_ml_model_info`
- pause further rollout until the version mismatch is understood

Follow-up action:
- tighten deployment checks for model version provenance
- document approved model version per release

### 5. Fraud-Scoring Fallback Spike

What breaks:
- scoring remains operational but ML-assisted path degrades
- compare/shadow diagnostics may become less representative

How to detect it:
- `fraud_scoring_fallbacks_total` ratio breaches threshold
- correlate with ML runtime availability, latency, and inference error metrics

Likely causes:
- ML service outage
- ML service returning unavailable model output
- Java-to-ML transport instability

Immediate response:
- separate transport failure from runtime failure:
  - if Java client `outcome="error"` rises, investigate transport/connectivity
  - if ML `/metrics` still shows traffic and errors, investigate runtime behavior

Follow-up action:
- tune fallback alerting after understanding whether spikes are operationally noisy
- verify fallback remained deterministic and safe during the incident

## Triage Steps

Use this order during ML runtime incidents:

1. Check `fraud_scoring_fallbacks_total` and `fraud_scoring_ml_client_requests_total` in Java.
2. Check ML request rate, error rate, and p95 latency from `/metrics`.
3. Verify `fraud_ml_model_info` and `fraud_ml_model_load_status`.
4. Inspect ML runtime logs for `score_failed`, startup, and health behavior.
5. Use `correlationId` in logs only after metrics identify the failing path.

## Java Integration Note

`fraud-scoring-service` already exposes enough fallback and ML-client telemetry for FDP-5:

- `fraud_scoring_ml_client_requests_total`
- `fraud_scoring_ml_client_latency_seconds`
- `fraud_scoring_fallbacks_total`

No Java runtime behavior change is required. Java should continue using existing client behavior and fallback semantics rather than scraping ML `/metrics`.

## Known Limitations

- `ml-inference-service` exposes runtime metrics only while the process is up; a hard startup failure still requires container logs or orchestration health signals.
- `fraud_ml_model_load_status{outcome="failure"}` is primarily useful for runtime visibility of the active metadata contract, not for post-crash introspection after the process exits.
- The shipped dashboard covers runtime health and fallback visibility, but not model quality analysis.
- No alert manager or delivery routing is shipped in-repo.
- Metrics do not describe model quality, calibration drift, or feature drift.
- Logs remain necessary for request-level investigation.
- First build on a new machine still requires Docker access to upstream registries for base images.
