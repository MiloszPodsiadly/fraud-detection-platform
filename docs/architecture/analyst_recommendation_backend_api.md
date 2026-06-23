# Analyst Recommendation API and UI Contract

Status: FDP-119 implemented backend/API/frontend read-only contract.

FDP-119 adds an advisory analyst recommendation contract to the scored transaction flow and exposes it in the
single-transaction Analyst Console read view. The source of truth is `fraud-scoring-service`, where
`AnalystRecommendationService` derives a bounded recommendation from the existing `FraudScoreResult` and
already-projected `EngineIntelligenceSummary`. Alert-service stores the value from `TransactionScoredEvent` and
exposes it on the scored transaction detail API only. The Analyst Console validates and renders the public DTO
read-only; it does not compute recommendation values.

## Runtime Flow

1. `fraud-scoring-service` computes the existing fraud score.
2. The existing Engine Intelligence emission path may provide `EngineIntelligenceSummary`.
3. `AnalystRecommendationService` produces `AnalystRecommendationResult`.
4. `TransactionScoredEvent.analystRecommendation` carries the optional result.
5. `alert-service` persists the event value in `ScoredTransactionDocument`.
6. `GET /api/v1/transactions/scored/{transactionId}` returns `analystRecommendation`.
7. Analyst Console renders `analystRecommendation` in the Transaction Risk Intelligence detail panel.

`GET /api/v1/transactions/scored` remains lightweight and does not expose `analystRecommendation`.

## Status Semantics

- `AVAILABLE`: enough projected diagnostic context exists and a bounded advisory recommendation is present.
- `DEGRADED`: a recommendation is present, but Engine Intelligence carried warnings or degraded engine status.
- `ABSENT`: old events or disabled diagnostic periods have no recommendation; this is not `RECOMMEND_NO_ACTION`.
- `INSUFFICIENT_DATA`: projected diagnostic context exists but is not enough to create an advisory recommendation.
- `UNAVAILABLE`: the recommendation or Engine Intelligence read/generation path degraded.
- `NOT_APPLICABLE`: projected context is present but no comparable supported engines are available.

`AVAILABLE` and `DEGRADED` require at least one reason code. `ABSENT`, `NOT_APPLICABLE`, `INSUFFICIENT_DATA`, and
`UNAVAILABLE` must not carry a recommendation value. `RECOMMEND_NO_ACTION` means only that this advisory layer has no
additional review suggestion. It is not transaction approval, payment authorization, or fraud clearance.

## Boundaries

This layer is advisory-only. It does not authorize payments, approve, decline, block, create fraud cases, mutate
workflow, capture analyst feedback, promote models, recommend thresholds, change scoring, or recompute Engine
Intelligence. The public contract carries explicit non-decisioning flags and bounded reason/warning arrays.

Alert-service must not recompute the recommendation. Missing stored values are mapped to `ABSENT` only for old
projection compatibility.

The frontend must not contain recommendation logic, scoring logic, case/workflow mutations, feedback behavior, payment
authorization behavior, model promotion, or threshold recommendation behavior. It validates the public DTO shape,
rejects unsafe raw/internal fields, and displays only read-only advisory text.

The recommendation is exposed through scored transaction detail reads and is covered by the existing scored transaction
detail read audit path. No high-cardinality recommendation metrics are emitted by this baseline.

Known future hardening: the recommendation DTO does not currently carry a dedicated `recommendationVersion` or
`generatedAt` field. Adding those fields would require propagation through the event, persistence, API schema, frontend
validation, fixtures, and compatibility tests.
