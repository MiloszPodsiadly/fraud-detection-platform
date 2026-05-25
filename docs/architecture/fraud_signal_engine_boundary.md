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
is a canonical lowercase allowlisted implementation language. Aliases are rejected instead of
normalized: use `cpp` instead of `C++`, `csharp` instead of `C#`, `javascript` instead of
`js`/`node`/`nodejs`, and `typescript` instead of `ts`.

`required` is descriptive only in FDP-84. It has no runtime fallback semantics, no failure
semantics, no routing semantics, and no decisioning semantics. Orchestrator policy belongs to a
later branch.

## FeatureSnapshot Consumption Precondition

FDP-84 does not authorize adapters to consume `featureSnapshot`. It remains the existing internal
runtime shape. A featureSnapshot consumption policy must be defined before adapters; it must state
allowed keys, allowed value types, raw payload boundaries, missing/unknown/unavailable semantics,
and nested structure policy.

## Out Of Scope

No `RuleBasedSignalEngine`, `PythonMlSignalEngine`, `FraudScoringOrchestrator`, or
`FraudIntelligenceResult` is included. There is no `engineResults[]`, no
`TransactionScoredEvent` change, no Kafka event change, no alert-service projection, no API/UI,
no feedback loop, no scoring mode change, no fallback behavior change, no ML availability behavior
change, no automated approval or decline, and no final payment decisioning.
