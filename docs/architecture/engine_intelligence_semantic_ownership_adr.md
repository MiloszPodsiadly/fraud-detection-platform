# Engine Intelligence Semantic Ownership ADR

Status: accepted for FDP-129.

## Context

Engine Intelligence now spans feature facts, Rules, Python ML, optional Velocity diagnostics, public event mapping,
alert-service projection, bounded API/OpenAPI, and Analyst Console rendering. The same fraud fact can otherwise be
double-counted through a rule flag, recent-count feature, and per-minute-rate feature, while optional-engine failures
can accidentally look like low risk.

## Decision

Feature Enricher owns factual observations only. It provides bounded facts such as `recentTransactionCount`,
`recentTransactionCountWindow`, `recentAmountSumPln`, and `transactionVelocityPerMinute`; it does not decide fraud
semantics, Velocity severity, Rules/ML comparison, analyst recommendations, or final actions.

Rules owns baseline business-rule interpretation and the single Rules contribution for a given underlying fact. Rules
may use the same official rapid-transfer threshold facts as other components, but it must not emit multiple
contributions for the same semantic signal.

FDP-129 Stage 1 keeps the production Rules model at `rule-based-engine` / `v1` and the adapter descriptor at
`rules.primary` / `1.0.0`. The frozen Rules V1 compatibility matrix is captured in
`fraud-scoring-service/src/test/resources/fixtures/rules/rules_v1_baseline_matrix.json`; it preserves the historical
official Feature Enricher to Rules outcomes while consolidating duplicate velocity, recent-amount, and rapid-transfer
contributions into one Rules contribution per semantic fact.

Python ML owns bounded ML score context only. The current public comparison identity is explicitly `RULES_VS_ML` with
`comparedEngineIds=["rules.primary","ml.python.primary"]`; it is not generic all-engine agreement.

VelocitySignalPolicy owns Velocity V1 semantics. Velocity requires `recentTransactionCountWindow=PT1M`; count, window,
and per-minute rate must be mutually consistent when all are present. Inconsistent present values degrade Velocity
with `VELOCITY_FEATURES_INCONSISTENT`. Velocity score is deterministic normalized risk severity, not calibrated fraud
probability and not model confidence.

The orchestrator owns optional-engine failure isolation and execution metadata. Optional Velocity failures must not
break Rules/ML diagnostic runtime execution or baseline scoring. Published `latencyMs` comes from monotonic elapsed
measurement; `generatedAt` is a wall-clock timestamp and must not be used as the latency source.

The public event contract owns the atomic `contractVersion=1` shape under the documented assumption that supported
consumers are repository-controlled and migrated atomically. Version 1 allows three known engine identities:
`rules.primary`, `ml.python.primary`, and `velocity.primary`. Rules and ML are required. Velocity is an optional third
diagnostic engine. Any fourth engine, changed order, changed comparison identity, changed PT1M meaning, or changed
Velocity probability claim requires an explicit versioned contract review.

## Rejected Alternatives

- Score the same rapid-transfer anomaly separately through flag, count, and rate semantics.
- Treat agreement as a generic all-engine comparison without comparison identity.
- Include Velocity in `RULES_VS_ML` score-delta semantics.
- Silently disable Velocity under contradictory configuration or contradictory present feature facts.
- Treat Velocity severity as calibrated fraud probability or model confidence.
- Infer low risk, zero score, or safe status from unavailable, timeout, skipped, degraded, or missing engine results.

## Non-goals

This ADR does not change authentication, authorization, tenant isolation, payment authorization, automatic
approve/decline/block behavior, fraud-case workflow, model retraining, rule update workflow, external attestation, or
compatibility guarantees for unknown external consumers.
