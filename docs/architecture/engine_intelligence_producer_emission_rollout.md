# Controlled Engine Intelligence Producer Emission Rollout

Status: FDP-94 disabled-by-default producer capability boundary only.

## Purpose

FDP-94 adds a controlled producer mapping capability for the bounded public `engineIntelligence`
summary already defined by FDP-92 and tolerated by consumers under FDP-93. It does not enable
production runtime emission by default.

## Rollout Flag

The only producer rollout flag is:

```text
fraud.scoring.events.engine-intelligence.emit-enabled=false
```

The property is intentionally specific to scoring event emission. Missing and explicit `false`
values keep emission disabled. Explicit `true` permits the bounded mapping capability only.
The environment override is:

```text
FRAUD_SCORING_EVENTS_ENGINE_INTELLIGENCE_EMIT_ENABLED
```

## Mapping Boundary

`TransactionScoredEventMapper` accepts an optional public `EngineIntelligenceSummary`.
An empty optional keeps the pre-FDP-94 event shape and omits the `engineIntelligence` JSON field.
A present optional adds only the bounded public DTO. Internal aggregation objects, raw evidence,
contributions, and internal diagnostics are not event payload fields.

## Runtime Limitation

FDP-94 does not migrate baseline scoring runtime to `FraudScoringOrchestrator`.
The live `TransactionFraudScoringService` path intentionally keeps the existing two-argument
mapper call. An orchestration aggregation result is not currently available in that live path.
Runtime orchestration emission requires a separate reviewed future branch.

## Failure Isolation

Optional enrichment failures return the base scored event without `engineIntelligence`.
Failure logging is bounded and does not include raw exception messages. Baseline scoring errors
remain baseline scoring errors and are not swallowed by optional enrichment handling.

## Rollout Sequence

1. Keep `fraud.scoring.events.engine-intelligence.emit-enabled=false`.
2. Verify FDP-92 public-contract and FDP-93 consumer-readiness tests remain green.
3. Add separately reviewed live runtime orchestration wiring only when the aggregation result is
   already available without changing baseline scoring decisions.
4. Enable emission in an explicitly controlled environment after payload and consumer validation.

## Rollback

Set `fraud.scoring.events.engine-intelligence.emit-enabled=false` and redeploy. Disabled mode omits
the nested JSON field and restores the old emitted event shape. No alert-service projection,
persistence, API, or UI rollback is required because FDP-94 does not add those capabilities.

## Scope Guardrails

- No alert-service projection or persistence.
- No API or analyst-console UI exposure.
- No final decisioning, automatic approve, automatic decline, or payment authorization.
- No production migration to `FraudScoringOrchestrator`.
- No raw or internal aggregation serialization.
