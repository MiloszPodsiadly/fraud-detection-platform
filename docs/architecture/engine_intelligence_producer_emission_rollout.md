# Controlled Engine Intelligence Producer Emission Rollout

Status: FDP-94 disabled-by-default runtime producer emission.

## Purpose

FDP-94 wires optional producer emission for the bounded public `engineIntelligence` summary already
defined by FDP-92 and tolerated by consumers under FDP-93. The runtime producer emission remains
disabled by default.

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

`TransactionScoredEventMapper` accepts an optional public `EngineIntelligenceSummary`.
An empty optional keeps the pre-FDP-94 event shape and omits the `engineIntelligence` JSON field.
A present optional adds only the bounded public DTO. Internal aggregation objects, raw evidence,
contributions, and internal diagnostics are not event payload fields.

## Runtime Boundary

Baseline scoring remains in the existing `FraudScoringEngine` path. Disabled mode keeps the
pre-FDP-94 serialized event shape, does not invoke orchestrator, aggregation, public mapper, rules,
or ML diagnostic path, and does not initialize the conditional diagnostic runtime graph. Enabled mode performs shadow
diagnostic orchestration after baseline scoring and attaches bounded public `engineIntelligence`.
Enabled mode may execute rule and ML signal engines in addition to baseline scoring.
Enabled mode may add latency, ML service calls, executor work, and operational load.

Enabled diagnostic results must not change baseline `fraudScore`, `riskLevel`, `alertRecommended`,
`reasonCodes`, `scoringEvidence`, or `scoreDetails`. Enabled diagnostic results may differ from the
baseline scoring result. Such disagreement is diagnostic only and not final decisioning.
Diagnostic enrichment is not scoring migration and does not feed back into the baseline result.

## Failure Isolation

Enrichment failure returns the base event without `engineIntelligence`. Failure logging is bounded
and does not include raw exception messages. Baseline scoring failures are not swallowed.

## Rollout Sequence

1. Keep `fraud.scoring.events.engine-intelligence.emit-enabled=false`.
2. Verify FDP-92 public-contract and FDP-93 consumer-readiness tests remain green.
3. Keep enabled mode disabled by default until latency and load are validated.
4. Enable emission gradually in an explicitly controlled environment after payload and consumer validation.
5. Verify latency, timeout, rejection, and enrichment-omission behavior before expanding rollout.

## Rollback

Set `fraud.scoring.events.engine-intelligence.emit-enabled=false` and redeploy. Disabled mode omits
the nested JSON field and restores the old emitted event shape. No alert-service projection,
persistence, API, or UI rollback is required because FDP-94 does not add those capabilities.

## Operational Observability Boundary

FDP-94 includes a no-op metrics boundary for disabled skips, enrichment attempts, successes,
omissions, and latency. Metrics recording is best-effort and cannot block event publishing.
Production metrics backend integration remains future scope. Before wider rollout, FDP-95/FDP-96
must connect the low-cardinality metrics boundary to production telemetry for:

- `enrichment_attempt_total`
- `enrichment_success_total`
- `enrichment_omitted_total`
- `enrichment_latency_seconds`
- `enrichment_timeout_total` if applicable

Labels must be low-cardinality only. Transaction, customer, and account IDs must not be metrics
labels.

## Scope Guardrails

- No alert-service projection or persistence.
- No API or analyst-console UI exposure.
- No final decisioning, automatic approve, automatic decline, or payment authorization.
- No migration of baseline scoring decisions to `FraudScoringOrchestrator`.
- No raw or internal aggregation serialization.
