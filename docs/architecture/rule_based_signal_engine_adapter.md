# Rule-Based Signal Engine Adapter

Status: historical FDP-87 adapter foundation, superseded for FDP-129 runtime semantics.

## Purpose

`RuleBasedSignalEngine` originally proved that rule-based scoring intent could be represented as a
`FraudSignalEngine` using `ScoringContext`, `FeatureSnapshotReader`, and internal
`FraudSignalEvaluation`.

FDP-87 statements below are historical foundation notes. FDP-129 wires this adapter into the
diagnostic Engine Intelligence runtime through `FraudScoringOrchestrator`; current runtime source
of truth is:

- [Feature snapshot consumption policy](feature_snapshot_consumption_policy.md)
- [Engine Intelligence semantic ownership ADR](engine_intelligence_semantic_ownership_adr.md)
- [Internal fraud scoring orchestrator](fraud_scoring_orchestrator.md)
- [Public engine intelligence event contract](public_engine_intelligence_event_contract.md)

The existing `RuleBasedFraudScoringEngine` remains the primary production scoring source of truth.
`RuleBasedSignalEngine` is diagnostic-only and does not authorize payments, mutate the final fraud
decision, promote models, update thresholds, or perform case/workflow actions.

`RuleBasedSignalEngine` is a true adapter around `RuleBasedFraudScoringEngine`. It delegates
scoring to the production rule engine and maps the production score, risk level, model identity,
and supported production reason codes into `FraudSignalEvaluation`. It must not keep independent
weights, high thresholds, critical thresholds, or local score calculations.

FDP-87 introduced no runtime scoring behavior changes. FDP-129 supersedes that historical non-goal:
diagnostic runtime wiring, public bounded event summaries, alert-service projection/API, and Analyst
Console rendering now exist elsewhere in the current architecture. This document must not be used to
claim that those FDP-129 runtime components are absent.

## Feature Consumption

`RuleBasedSignalEngine` must use `FeatureSnapshotReader` through `FeatureSnapshotReaderFactory`.
It must not call `context.featureSnapshot().get(...)`, must not cast raw `Map<String, Object>`,
and must not use `FeatureSnapshotKeyPolicy.isAllowedFeatureKey` as permission to consume features.

FDP-129 preflight covers the canonical Rules V1 snapshot facts consumed by production Rules:
`recentTransactionCount`, `recentTransactionCountWindow`, `recentAmountSumPln`,
`recentAmountSumWindow`, `rapidTransferCount`, `rapidTransferTotalPln`, `rapidTransferWindow`, and
`rapidTransferFraudCaseCandidate`. Present-invalid canonical facts fail closed. Missing legacy data
may use only the narrow compatibility paths documented in
`feature_snapshot_consumption_policy.md`.

Production `RuleBasedFraudScoringEngine` still delegates validation to `RulesFeatureInputValidator`
before scoring. Primary scoring failure and diagnostic adapter degradation are intentionally
different runtime boundaries.

Feature status semantics:

- `PRESENT` may produce a bounded rule signal.
- `MISSING` skips the rule and is not false, not zero, and not low risk.
- `INVALID_TYPE` is not coerced and returns a bounded `DEGRADED` result.
- `WRONG_ACCESSOR` is an implementation bug and must fail fast.
- `NOT_ALLOWED` is an implementation bug and must fail fast.

The isolated adapter does not assign public `generatedAt` or `latencyMs`. FDP-129 runtime
publication is owned by `FraudScoringOrchestrator`, which publishes bounded engine-result
`generatedAt` and `latencyMs` from the injected execution `Clock`.

## Evidence Safety

The adapter emits bounded reason codes from production and safe identifiers only. It does not
expose raw feature values, customerSegment raw value, merchantCategory raw value, currency raw
value, amount raw values, transaction IDs, score details, raw payload/debug/exception text,
tokens, or secrets.

## Operational Semantics

Primary scoring:

- malformed required Rules input fails closed with bounded `RulesFeatureInputValidationException`;
- Kafka processing may retry and route to DLT through existing listener/error-handler policy;
- no fake scored event is fabricated.

Diagnostic Engine Intelligence:

- invalid Rules adapter input becomes a bounded `DEGRADED` required-engine result;
- Rules score and risk are null for operational degradation;
- eligible ML diagnostics can still execute;
- public Engine Intelligence does not expose raw exception text or raw feature values.

## Historical FDP-87 Out Of Scope

FDP-87 does not include `PythonMlSignalEngine`, `FraudScoringOrchestrator`,
`FraudIntelligenceResult`, `engineResults[]`, `TransactionScoredEvent` changes, Kafka event
changes, alert-service projection, API/UI, feedback loop, scoring mode changes, fallback behavior
changes, automatic approve/decline, or final payment decisioning.

## Next

FDP-88 may add a Python ML adapter only after this rule adapter remains isolated and tested.
