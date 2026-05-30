# Rule-Based Signal Engine Adapter

Status: FDP-87 adapter foundation only.

## Purpose

`RuleBasedSignalEngine` proves that current rule-based scoring intent can be represented as a
`FraudSignalEngine` using `ScoringContext`, `FeatureSnapshotReader`, and `FraudEngineResult`.

The adapter is internal to `fraud-scoring-service`. It is not a Spring component, is not wired
into `CompositeFraudScoringEngine`, and is not production scoring source of truth. The existing
`RuleBasedFraudScoringEngine` remains production source of truth.

FDP-87 introduces no runtime scoring behavior changes, no orchestrator, no Python ML adapter, no
event/API/UI/projection changes, and no `engineResults[]`.

## Feature Consumption

`RuleBasedSignalEngine` must use `FeatureSnapshotReader` through `FeatureSnapshotReaderFactory`.
It must not call `context.featureSnapshot().get(...)`, must not cast raw `Map<String, Object>`,
and must not use `FeatureSnapshotKeyPolicy.isAllowedFeatureKey` as permission to consume features.

Feature status semantics:

- `PRESENT` may produce a bounded rule signal.
- `MISSING` skips the rule and is not false, not zero, and not low risk.
- `INVALID_TYPE` is not coerced.
- `WRONG_ACCESSOR` is an implementation bug.
- `NOT_ALLOWED` is an implementation bug.

## Evidence Safety

The adapter emits bounded reason codes and safe identifiers only. It does not expose raw feature
values, customerSegment raw value, merchantCategory raw value, currency raw value, amount raw
values, transaction IDs, raw payload/debug/exception text, tokens, or secrets.

## Out Of Scope

FDP-87 does not include `PythonMlSignalEngine`, `FraudScoringOrchestrator`,
`FraudIntelligenceResult`, `engineResults[]`, `TransactionScoredEvent` changes, Kafka event
changes, alert-service projection, API/UI, feedback loop, scoring mode changes, fallback behavior
changes, automatic approve/decline, or final payment decisioning.

## Next

FDP-88 may add a Python ML adapter only after this rule adapter remains isolated and tested.
