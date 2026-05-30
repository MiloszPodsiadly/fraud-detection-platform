# Fraud Signal Engine Boundary

Status: FDP-84 internal interface foundation only.

## Purpose

`FraudSignalEngine` is the internal runtime interface that future fraud engines will implement. It
connects `ScoringContext` as input, `FraudEngineResult` as output, and
`FraudEngineDescriptor` as static engine identity/capability metadata.

`FraudSignalEngine` belongs in `fraud-scoring-service`. It is not a `common-events` contract, not a
Kafka event, not an API DTO, not an orchestrator, not a decisioning mechanism, and not a final
banking decision source. This foundation makes no runtime scoring behavior change.

## Descriptor Semantics

`FraudEngineDescriptor` describes static engine identity and static metadata. Its `engineLanguage`
is a canonical lowercase allowlisted implementation language. Allowed values in FDP-84 are:
`java`, `python`, `go`, `kotlin`, `scala`, `javascript`, and `other`.

`FraudEngineDescriptor.engineLanguage` must remain aligned with
`FraudEngineResult.engineLanguage` because `FraudSignalEngine.evaluate()` returns
`FraudEngineResult`. Aliases are rejected instead of normalized. Unsupported implementation
languages must use `other` until the shared `FraudEngineResult` language policy is intentionally
expanded; `other` is not a preferred language value.

`engineLanguage` is metadata only. An allowlisted language does not imply runtime support, does
not imply deployment support, does not imply execution sandboxing, does not imply operational
approval, and does not imply bank approval for that implementation language.

`required` is descriptive only in FDP-84. It has no runtime fallback semantics, no failure
semantics, no routing semantics, and no decisioning semantics. Orchestrator policy belongs to a
later branch.

## FeatureSnapshot Consumption Precondition

FDP-84 does not authorize adapters to consume `featureSnapshot`. It remains the existing internal
runtime shape. A featureSnapshot consumption policy must be defined before adapters; it must state
allowed keys, allowed value types, raw payload boundaries, missing/unknown/unavailable semantics,
and nested structure policy.

## Current Adapter Boundary

FDP-84 introduced the interface foundation only. FDP-87 adds an isolated `RuleBasedSignalEngine`
adapter documented in `docs/architecture/rule_based_signal_engine_adapter.md`; it is not a Spring
component, is not wired into runtime scoring, and does not make `FraudSignalEngine` an orchestrated
production scoring path.

FDP-88 adds an isolated `PythonMlSignalEngine` adapter documented in
`docs/architecture/python_ml_signal_engine_adapter.md`; it delegates to the existing ML scoring
boundary, is not a Spring component, is not wired into runtime scoring, and does not make ML a
final decision source.

## Out Of Scope

No `FraudScoringOrchestrator` or `FraudIntelligenceResult` is included. There is no
`engineResults[]`, no `TransactionScoredEvent` change, no Kafka event change, no alert-service
projection, no API/UI, no engine wrappers, no feedback loop, no scoring mode change, no fallback
behavior change, no automated approval or decline, and no final payment decisioning.
