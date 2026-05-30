# Rule-Based Signal Engine Adapter

Status: FDP-87 adapter foundation only.

## Purpose

`RuleBasedSignalEngine` proves that current rule-based scoring intent can be represented as a
`FraudSignalEngine` using `ScoringContext`, `FeatureSnapshotReader`, and `FraudEngineResult`.

The adapter is internal to `fraud-scoring-service`. It is not a Spring component, is not wired
into `CompositeFraudScoringEngine`, and is not a production runtime path. The existing
`RuleBasedFraudScoringEngine` remains production source of truth.

`RuleBasedSignalEngine` is a true adapter around `RuleBasedFraudScoringEngine`. It delegates
scoring to the production rule engine and maps the production score, risk level, model identity,
and supported production reason codes into `FraudEngineResult`. It must not keep independent
weights, high thresholds, critical thresholds, or local score calculations.

FDP-87 introduces no runtime scoring behavior changes, no orchestrator, no Python ML adapter, no
event/API/UI/projection changes, and no `engineResults[]`.

## Feature Consumption

`RuleBasedSignalEngine` must use `FeatureSnapshotReader` through `FeatureSnapshotReaderFactory`.
It must not call `context.featureSnapshot().get(...)`, must not cast raw `Map<String, Object>`,
and must not use `FeatureSnapshotKeyPolicy.isAllowedFeatureKey` as permission to consume features.

Preflight is intentionally limited to snapshot keys that production rule engine actually consumes
from `featureSnapshot`. At this stage, that means `rapidTransferFraudCaseCandidate` only. The
adapter must not degrade based on invalid snapshot values for typed event fields, because
`RuleBasedFraudScoringEngine` consumes those fields from `TransactionEnrichedEvent`. This avoids
semantic drift between the adapter and production rule engine.

FDP-87 does not refactor production `RuleBasedFraudScoringEngine` to consume all features through
`FeatureSnapshotReader`. That refactor, if ever desired, belongs to a separate migration branch.

Feature status semantics:

- `PRESENT` may produce a bounded rule signal.
- `MISSING` skips the rule and is not false, not zero, and not low risk.
- `INVALID_TYPE` is not coerced and returns a bounded `DEGRADED` result.
- `WRONG_ACCESSOR` is an implementation bug and must fail fast.
- `NOT_ALLOWED` is an implementation bug and must fail fast.

Generated timestamps and latency are deterministic for this isolated adapter: `generatedAt` comes
from `ScoringContext.receivedAt()`, and `latencyMs` is `0`.

## Evidence Safety

The adapter emits bounded reason codes from production and safe identifiers only. It does not
expose raw feature values, customerSegment raw value, merchantCategory raw value, currency raw
value, amount raw values, transaction IDs, score details, raw payload/debug/exception text,
tokens, or secrets.

## Out Of Scope

FDP-87 does not include `PythonMlSignalEngine`, `FraudScoringOrchestrator`,
`FraudIntelligenceResult`, `engineResults[]`, `TransactionScoredEvent` changes, Kafka event
changes, alert-service projection, API/UI, feedback loop, scoring mode changes, fallback behavior
changes, automatic approve/decline, or final payment decisioning.

## Next

FDP-88 may add a Python ML adapter only after this rule adapter remains isolated and tested.
