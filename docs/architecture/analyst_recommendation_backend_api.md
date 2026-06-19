# Analyst Recommendation Backend/API Foundation

Status: FDP-119 implemented backend/API contract.

FDP-119 adds an advisory analyst recommendation contract to the scored transaction flow. The source of truth is
`fraud-scoring-service`, where `AnalystRecommendationService` derives a bounded recommendation from the existing
`FraudScoreResult` and already-projected `EngineIntelligenceSummary`. Alert-service stores the value from
`TransactionScoredEvent` and exposes it on the scored transaction detail API only.

## Runtime Flow

1. `fraud-scoring-service` computes the existing fraud score.
2. The existing Engine Intelligence emission path may provide `EngineIntelligenceSummary`.
3. `AnalystRecommendationService` produces `AnalystRecommendationResult`.
4. `TransactionScoredEvent.analystRecommendation` carries the optional result.
5. `alert-service` persists the event value in `ScoredTransactionDocument`.
6. `GET /api/v1/transactions/scored/{transactionId}` returns `analystRecommendation`.

`GET /api/v1/transactions/scored` remains lightweight and does not expose `analystRecommendation`.

## Status Semantics

- `AVAILABLE`: enough projected diagnostic context exists and a bounded advisory recommendation is present.
- `DEGRADED`: a recommendation is present, but Engine Intelligence carried warnings or degraded engine status.
- `ABSENT`: old events or disabled diagnostic periods have no recommendation; this is not `RECOMMEND_NO_ACTION`.
- `INSUFFICIENT_DATA`: projected diagnostic context exists but is not enough to create an advisory recommendation.
- `UNAVAILABLE`: the recommendation or Engine Intelligence read/generation path degraded.
- `NOT_APPLICABLE`: projected context is present but no comparable supported engines are available.

## Boundaries

This layer is advisory-only. It does not authorize payments, approve, decline, block, create fraud cases, mutate
workflow, capture analyst feedback, promote models, recommend thresholds, change scoring, or recompute Engine
Intelligence. The public contract carries explicit non-decisioning flags and bounded reason/warning arrays.

Alert-service must not recompute the recommendation. Missing stored values are mapped to `ABSENT` only for old
projection compatibility.
