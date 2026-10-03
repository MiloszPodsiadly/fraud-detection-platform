# Controlled Engine Intelligence Producer Emission Rollout

Status: current disabled-by-default runtime producer emission.

## Purpose

The scoring producer can emit the bounded public `engineIntelligence` summary after consumer compatibility has
been established. Runtime producer emission remains disabled by default.

## Rollout Flag

The only producer rollout flag is:

```text
fraud.scoring.events.engine-intelligence.emit-enabled=false
```

The property is intentionally specific to scoring event emission. Missing config means disabled.
Explicit `false` means disabled. Explicit `true` enables producer-side diagnostic enrichment.
The environment override is:

```text
FRAUD_SCORING_EVENTS_ENGINE_INTELLIGENCE_EMIT_ENABLED
```

## Mapping Boundary

`TransactionScoredEventMapper` accepts an optional public `EngineIntelligenceSummary` and optional internal
`MlPredictionEvidenceV1`.
An empty optional keeps the evidence-free event shape and omits the `engineIntelligence` JSON field.
A present summary adds only the bounded public DTO. Internal aggregation objects, raw model payloads,
contributions, and internal diagnostics are not event payload fields.

Internal prediction evidence capture may additionally carry `MlPredictionEvidenceV1` for an `AVAILABLE` `ml.python.primary` result. It exists
to preserve the exact diagnostic ML output for later governed evaluation; it is not another public Engine
Intelligence score. Its only authority is the `FraudEngineResult` returned by the same orchestrator execution used
for aggregation. The evidence preserves that result's exact bounded score, complete model and feature-contract
identity, engine ID, risk level, and execution timestamp. It is never reconstructed from the public score bucket,
score delta, top-level platform `fraudScore`, registry state, or another ML call.

The exact ML score may differ from the platform `fraudScore` because baseline scoring and diagnostic execution are
separate responsibilities. The platform score remains authoritative for the scored event and alert recommendation.

## Runtime Boundary

Baseline scoring remains in the existing `FraudScoringEngine` path.

Disabled mode keeps the evidence-free serialized event shape and emits neither Engine Intelligence nor ML prediction
evidence.
It does not invoke orchestrator, aggregation, public mapper, rules, or ML diagnostic path.
It does not initialize the conditional diagnostic runtime graph.

Enabled mode performs shadow diagnostic orchestration after baseline scoring.
It attaches bounded public `engineIntelligence`.
Enabled mode may execute rule and ML signal engines in addition to baseline scoring.
Enabled mode may add latency, ML service calls, executor work, and operational load.

Enabled diagnostic results must not change baseline `fraudScore`, `riskLevel`, `alertRecommended`,
`reasonCodes`, `scoringEvidence`, or `scoreDetails`. Enabled diagnostic results may differ from the
baseline scoring result. Such disagreement is diagnostic only and not final decisioning.
Diagnostic enrichment is not scoring migration and does not feed back into the baseline result.

## Failure Isolation

Enrichment failure returns the base event without `engineIntelligence` or ML prediction evidence. Non-AVAILABLE ML
statuses are represented in the bounded public summary but do not manufacture exact prediction evidence. Failure logging is bounded
and does not include raw exception messages. Baseline scoring failures are not swallowed.

Current behavior is passive capture and persistence for evaluation. It does not train, promote, calibrate, or activate
a model and does not change decision authority. Future governed evaluation or promotion may consume the evidence only
through a separately reviewed contract.

## Rollout Sequence

1. Keep `fraud.scoring.events.engine-intelligence.emit-enabled=false`.
2. Verify public-contract and consumer-readiness tests remain green.
3. Keep enabled mode disabled by default until latency and load are validated.
4. Enable emission gradually in an explicitly controlled environment after payload and consumer validation.
5. Verify latency, timeout, rejection, and enrichment-omission behavior before expanding rollout.

## Rollback

Set `fraud.scoring.events.engine-intelligence.emit-enabled=false` and redeploy. Disabled mode omits
both optional diagnostic fields and restores the prior emitted event shape. Existing alert-service projections and
private evidence remain governed by their retention policy; rollback does not delete or rewrite accepted records.

## Operational Observability Boundary

The producer includes a no-op metrics boundary for disabled skips, enrichment attempts, successes,
omissions, and latency. Metrics recording is best-effort and cannot block event publishing.
Production metrics backend integration remains future scope. Before wider rollout, projection and API owners
must connect the low-cardinality metrics boundary to production telemetry for:

- `enrichment_attempt_total`
- `enrichment_success_total`
- `enrichment_omitted_total`
- `enrichment_latency_seconds`
- `enrichment_timeout_total` if applicable

`recordSuccess` means a public `EngineIntelligenceSummary` was actually produced. A completed
diagnostic pipeline that returns empty is recorded as a bounded omission. Enabled enrichment
attempts record latency for success, empty result, missing pipeline, and failure. Disabled skips do
not record enrichment attempt latency.

The producer records `UNKNOWN_FAILURE` for runtime pipeline failures. Stage-specific omission reasons are
reserved for future pipeline instrumentation. Current omission reasons remain bounded and
low-cardinality. Raw exception messages are not used as omission reasons.

Metrics are best-effort and must not affect event publishing. Metrics must remain low-cardinality.
Metrics must not include transaction IDs, customer IDs, account IDs, raw exception messages,
endpoint URLs, payloads, or feature vectors.

## Scope Guardrails

- No public exposure of exact ML evidence through API or Analyst Console UI.
- Alert-service persistence is owned by the separate projection boundary, not by the scoring producer.
- No final decisioning, automatic approve, automatic decline, or payment authorization.
- No migration of baseline scoring decisions to `FraudScoringOrchestrator`.
- No raw or internal aggregation serialization.
