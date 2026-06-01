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
pre-FDP-94 serialized event shape and does not invoke orchestrator, aggregation, or public mapper.
Enabled mode may invoke diagnostic enrichment after baseline scoring and attach bounded public
`engineIntelligence`. Enabled mode must not change baseline `fraudScore`, `riskLevel`,
`alertRecommended`, `reasonCodes`, `scoringEvidence`, or `scoreDetails`. Diagnostic enrichment is
not scoring migration and does not feed back into the baseline result.

## Failure Isolation

Enrichment failure returns the base event without `engineIntelligence`. Failure logging is bounded
and does not include raw exception messages. Baseline scoring failures are not swallowed.

## Rollout Sequence

1. Keep `fraud.scoring.events.engine-intelligence.emit-enabled=false`.
2. Verify FDP-92 public-contract and FDP-93 consumer-readiness tests remain green.
3. Enable emission in an explicitly controlled environment after payload and consumer validation.
4. Verify diagnostic enrichment latency before expanding rollout.

## Rollback

Set `fraud.scoring.events.engine-intelligence.emit-enabled=false` and redeploy. Disabled mode omits
the nested JSON field and restores the old emitted event shape. No alert-service projection,
persistence, API, or UI rollback is required because FDP-94 does not add those capabilities.

## Scope Guardrails

- No alert-service projection or persistence.
- No API or analyst-console UI exposure.
- No final decisioning, automatic approve, automatic decline, or payment authorization.
- No migration of baseline scoring decisions to `FraudScoringOrchestrator`.
- No raw or internal aggregation serialization.
