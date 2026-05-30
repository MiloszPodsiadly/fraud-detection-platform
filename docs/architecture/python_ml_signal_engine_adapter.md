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

On successful available output, FDP-88 maps supported source reason codes from
`MlFraudScoringEngine`. Unsupported or raw source reason strings are dropped without being
exposed. If no supported source reason remains, the adapter falls back to `ML_MODEL_SIGNAL`.

The ML source of truth owns feature extraction for this branch. The adapter does not read
`featureSnapshot`, does not use `FeatureSnapshotReader`, and does not preflight snapshot keys.
It must not call `context.featureSnapshot().get(...)`, must not cast raw `Map<String, Object>`,
and must not use `FeatureSnapshotKeyPolicy.isAllowedFeatureKey` as permission to consume features.

## Unavailable Semantics

ML unavailable is not low risk. ML timeout is not low risk. ML invalid response is not available.
Missing score is degraded. Score out of range is degraded. Missing model availability metadata is
degraded. Invalid model availability metadata is degraded. Raw exception details are never exposed.
When source output explicitly reports `modelAvailable=false`, the adapter returns
`ML_MODEL_UNAVAILABLE` before validating score, risk level, or model metadata; unavailable ML output
does not require those fields.

Unavailable and timeout outputs use bounded `UNAVAILABLE` or `TIMEOUT` status with null score,
null risk level, `UNKNOWN` confidence, deterministic `generatedAt` from `ScoringContext.receivedAt()`,
and `latencyMs` equal to `0`.

FDP-88 treats unexpected runtime exceptions from the isolated ML boundary as bounded
`ML_CLIENT_ERROR` because the adapter is not runtime-wired. Timeout-like exceptions are bounded as
`ML_MODEL_TIMEOUT`. No raw exception message or stacktrace is exposed. Future orchestrator/runtime
integration may narrow this exception taxonomy.

## Evidence Safety

The adapter emits bounded ML reason codes only. It exposes no raw model payload, no raw feature
vector, no request body, no response body, no stacktrace, no endpoint URL, no host/token/secret,
no customer/account/card identifiers, and no raw probabilities dump.

## Source Evidence Boundary

FDP-88 does not carry source scoringEvidence directly. Adapter evidence is generated from bounded
reason codes only. This avoids exposing raw ML diagnostics, feature vectors, payload fragments,
probabilities, endpoint details, or debug text.

Future evidence mapping requires an explicit bounded evidence policy and tests. FDP-88 still maps
source score, risk level, model metadata, and supported source reason codes.

## Out Of Scope

FDP-88 does not include `FraudScoringOrchestrator`, `FraudIntelligenceResult`, `engineResults[]`,
`TransactionScoredEvent` changes, Kafka event changes, alert-service projection, API/UI,
feedback loop, scoring mode changes, fallback behavior changes, automated approval or decline, or
final payment decisioning.

## Next

FDP-89 may add internal `FraudScoringOrchestrator` v1 only after rule and ML adapters remain
isolated and tested.
