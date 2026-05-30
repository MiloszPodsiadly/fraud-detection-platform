# Python ML Signal Engine Adapter

Status: FDP-88 adapter foundation only.

## Purpose

`PythonMlSignalEngine` proves that the existing ML scoring path can be represented as a
`FraudSignalEngine` using `ScoringContext` and `FraudEngineResult`.

The adapter is internal to `fraud-scoring-service`. It is not a Spring component, is not wired
into `CompositeFraudScoringEngine`, and is not production scoring path. The existing ML scoring
boundary remains source of truth.

FDP-88 introduces no runtime scoring behavior changes, no orchestrator, no event/API/UI/projection
changes, and no `engineResults[]`. ML is not final decision source.

## Source Of Truth

`PythonMlSignalEngine` delegates to the existing ML boundary through `MlFraudScoringEngine`. It
must not maintain independent thresholds, must not invent fallback decisions, and must not
reinterpret model probabilities outside existing ML semantics.

The ML source of truth owns feature extraction for this branch. The adapter does not read
`featureSnapshot`, does not use `FeatureSnapshotReader`, and does not preflight snapshot keys.
It must not call `context.featureSnapshot().get(...)`, must not cast raw `Map<String, Object>`,
and must not use `FeatureSnapshotKeyPolicy.isAllowedFeatureKey` as permission to consume features.

## Unavailable Semantics

ML unavailable is not low risk. ML timeout is not low risk. ML invalid response is not available.
Missing score is degraded. Score out of range is degraded. Raw exception details are never
exposed.

Unavailable and timeout outputs use bounded `UNAVAILABLE` or `TIMEOUT` status with null score,
null risk level, `UNKNOWN` confidence, deterministic `generatedAt` from `ScoringContext.receivedAt()`,
and `latencyMs` equal to `0`.

## Evidence Safety

The adapter emits bounded ML reason codes only. It exposes no raw model payload, no raw feature
vector, no request body, no response body, no stacktrace, no endpoint URL, no host/token/secret,
no customer/account/card identifiers, and no raw probabilities dump.

## Out Of Scope

FDP-88 does not include `FraudScoringOrchestrator`, `FraudIntelligenceResult`, `engineResults[]`,
`TransactionScoredEvent` changes, Kafka event changes, alert-service projection, API/UI,
feedback loop, scoring mode changes, fallback behavior changes, automated approval or decline, or
final payment decisioning.

## Next

FDP-89 may add internal `FraudScoringOrchestrator` v1 only after rule and ML adapters remain
isolated and tested.
