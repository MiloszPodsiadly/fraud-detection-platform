# Operations And Observability v1

DO NOT USE FOR CURRENT RUNTIME DECISIONS. This document is the historical baseline only.

Reviewer-friendly operations spec for the baseline observability foundation in `fraud-detection-platform`.

This document is still accurate for the original Java-service metrics and triage model, but it is no longer the latest runtime view after FDP-5. For the current local monitoring stack with Prometheus, Grafana, alert rules, and direct `ml-inference-service` metrics, use [operations-observability-v2.md](operations-observability-v2.md).

This document describes:

- what the platform measures today
- which dashboards should exist first
- which alerts should be added later
- how to triage incidents with the current metrics and logs

It intentionally does not include Grafana JSON exports or vendor-specific alert definitions because it describes the pre-rollout baseline, not the current shipped monitoring stack.

## Scope

Baseline foundation covers:

- Micrometer metrics in `alert-service`
- Micrometer metrics in `fraud-scoring-service`
- structured logs across services
- correlation and trace context propagation through HTTP and Kafka boundaries
- security telemetry for analyst-facing APIs

Baseline foundation did not yet cover:

- direct `/metrics` instrumentation inside `ml-inference-service`
- distributed tracing spans and trace export
- Grafana dashboard JSON
- SIEM integration
- production alert delivery channels

## Signals Implemented

### Alert Workflow

- `fraud.alert.decision.submissions`
- `fraud.alert.fraud_case.updates`
- `fraud.alert.audit.events`

### Security

- `fraud.security.auth.failures`
- `fraud.security.access.denied`
- `fraud.security.actor.mismatches`

### Scoring And ML Runtime

- `fraud.scoring.requests`
- `fraud.scoring.latency`
- `fraud.scoring.ml.client.requests`
- `fraud.scoring.ml.client.latency`
- `fraud.scoring.fallbacks`
- `fraud.scoring.ml.diagnostics.disagreements`

### Correlation / Tracing Foundation

- `correlationId` in HTTP and event payloads
- `traceId` propagation through Kafka headers
- MDC context for:
  - `correlationId`
  - `traceId`
  - `transactionId`
  - `alertId`

## Dashboard Spec

## Analyst Operations

### Dashboard: Analyst Workflow Health

Purpose:
- show whether analyst write paths are working and whether analyst actions are flowing normally

Key charts:
- `fraud.alert.decision.submissions` by `outcome`
- `fraud.alert.fraud_case.updates` by `outcome`
- `fraud.alert.audit.events` split by `action` and `outcome`
- optional overlay of `fraud.security.access.denied` for write endpoints

Use for triage:
- if decisions drop to zero but fraud cases still update, investigate alert decision path specifically
- if decisions succeed but audit volume drops, investigate audit publisher or logging path
- if access denied spikes during normal traffic, inspect recent RBAC/security changes

### Dashboard: Analyst Access Friction

Purpose:
- detect whether analyst users are being blocked by auth or permission regressions

Key charts:
- `fraud.security.auth.failures` by `reason`
- `fraud.security.access.denied` by `endpoint`
- `fraud.security.actor.mismatches` by `action`

Use for triage:
- `missing_credentials` spike usually points to client/session issues
- `invalid_jwt` spike points to token validation or issuer/key config issues
- `invalid_demo_auth` is mainly local/test misuse and should not appear in production-like setups
- actor mismatch growth suggests request payload identity is diverging from authenticated principal assumptions

## Platform Operations

### Dashboard: Event Pipeline Health

Purpose:
- confirm that the scoring and alert path is still processing traffic with acceptable latency

Key charts:
- `fraud.scoring.requests` by `mode`, `outcome`, and `fallback_used`
- `fraud.scoring.latency` by `mode`
- `fraud.alert.audit.events` as downstream analyst write activity confirmation
- infrastructure overlays later:
  - Kafka consumer lag
  - DLT volume

Use for triage:
- if scoring requests continue but analyst workflow drops, investigate `alert-service`
- if scoring latency rises before fallbacks rise, investigate degraded ML dependency before full outage
- if scoring requests disappear entirely, investigate upstream topic flow or listener availability

### Dashboard: Correlation And Processing Boundaries

Purpose:
- support log-based incident reconstruction with the new tracing foundation

Key panels:
- documentation-driven panel only for now:
  - HTTP ingress carries `X-Correlation-Id`
  - Kafka headers carry `correlationId`, `traceId`, `transactionId`, and `alertId` when applicable
  - listener and request boundaries populate MDC

Use for triage:
- start with `correlationId`
- if available, use `traceId` to follow the same logical request across Kafka boundaries
- pivot to `transactionId` or `alertId` only after correlation narrowing

## Security Monitoring

### Dashboard: Analyst API Security

Purpose:
- provide a small, security-relevant view of analyst API rejections and suspicious behavior

Key charts:
- `fraud.security.auth.failures` by `auth_type` and `reason`
- `fraud.security.access.denied` by `endpoint`
- `fraud.security.actor.mismatches` by `action`
- `fraud.alert.audit.events` by `action` and `outcome`

Use for triage:
- rising `invalid_jwt` with flat `403` usually means authentication breakage, not authorization drift
- rising `403` on one endpoint with stable auth success implies RBAC or permission rollout issue
- actor mismatches are not proof of abuse, but they are useful diagnostics for client misuse, stale payload assumptions, or impersonation attempts

## ML / Runtime Monitoring

### Dashboard: Scoring Runtime

Purpose:
- answer whether the platform is scoring requests on time and whether ML behavior is degrading

Key charts:
- `fraud.scoring.requests` by `mode`, `outcome`, and `fallback_used`
- `fraud.scoring.latency` by `mode`
- `fraud.scoring.fallbacks` by `mode` and `reason`
- `fraud.scoring.ml.client.requests` by `outcome=available|unavailable|error`
- `fraud.scoring.ml.client.latency` by `outcome`

Use for triage:
- `fallbacks` rising with `ml.client.requests{outcome=error}` means the ML dependency is failing hard
- `outcome=unavailable` without transport errors suggests the client reached the service but usable model output was not available
- rising client latency with stable success may indicate model runtime saturation before hard failure

### Dashboard: Shadow / Compare Diagnostics

Purpose:
- detect divergence between rule-based and ML-assisted behavior without changing production decisions

Key charts:
- `fraud.scoring.ml.diagnostics.disagreements{signal=decision}`
- `fraud.scoring.ml.diagnostics.disagreements{signal=risk_level}`
- `fraud.scoring.requests` filtered to `mode=shadow|compare`

Use for triage:
- a growing `decision` disagreement rate signals model-policy divergence worth offline review
- a growing `risk_level` disagreement rate signals calibration drift or threshold mismatch
- use logs with `correlationId` and `traceId` for sample-level investigation; do not try to solve this from metrics alone

## Alert Spec

These are proposed next-step alerts, not implemented routing rules.

## Analyst Operations Alerts

- Analyst decision volume drops unexpectedly.
  - Signal: `fraud.alert.decision.submissions`
  - Meaning: analyst workflow may be degraded or blocked

- Fraud case update volume drops unexpectedly.
  - Signal: `fraud.alert.fraud_case.updates`
  - Meaning: reviewer flow may be degraded

## Platform Operations Alerts

- Scoring latency sustained above normal.
  - Signal: `fraud.scoring.latency`
  - Meaning: downstream ML or local scoring path degradation

- Scoring fallback rate above threshold.
  - Signal: `fraud.scoring.fallbacks`
  - Meaning: ML runtime instability or availability regression

- ML client error rate above threshold.
  - Signal: `fraud.scoring.ml.client.requests{outcome=error}`
  - Meaning: transport-level failure to ML inference dependency

## Security Alerts

- JWT invalidation spike.
  - Signal: `fraud.security.auth.failures{reason=invalid_jwt}`
  - Meaning: issuer/JWK/token rollout breakage or malicious traffic spike

- Endpoint-specific access denied spike.
  - Signal: `fraud.security.access.denied`
  - Meaning: RBAC regression, UI misuse, or abnormal probing

- Actor mismatch spike.
  - Signal: `fraud.security.actor.mismatches`
  - Meaning: client/server identity contract drift or suspicious request composition

## ML / Runtime Alerts

- ML unavailable ratio rises.
  - Signal: `fraud.scoring.ml.client.requests{outcome=unavailable}`
  - Meaning: inference is reachable but not producing usable available output

- Compare/shadow disagreement rises materially.
  - Signal: `fraud.scoring.ml.diagnostics.disagreements`
  - Meaning: model behavior is drifting away from platform expectations

## Triage Guidance

Use this order during incidents:

1. Check `fraud.scoring.requests` and `fraud.scoring.latency`.
2. Check `fraud.scoring.fallbacks` and `fraud.scoring.ml.client.requests`.
3. Check analyst-side write metrics in `alert-service`.
4. Check security rejection metrics if analyst workflows are failing.
5. Reconstruct the incident with logs using `correlationId` first and `traceId` second.

## What We Still Do Not Monitor

- direct Prometheus-style metrics inside `ml-inference-service`
- Kafka lag and broker health in repo-local dashboards
- DLT volume as a first-class application metric
- span-level distributed tracing
- model quality, drift, and data distribution monitoring
- persistent security analytics outside logs and counters

## Reviewer Notes

This v1 spec is intentionally small. It gives the reviewer confidence that:

- the implemented metrics map to real operational questions
- security telemetry is not just logs anymore
- scoring/ML degradation can be seen before the whole platform fails
- correlation and trace context now have a stable shared contract

The next step after this document is cleanup and review hardening, not more instrumentation sprawl.
