# Internal Fraud Scoring Orchestrator v1

Status: FDP-89 internal-only orchestration foundation.

## Purpose

`FraudScoringOrchestrator` executes isolated `FraudSignalEngine` adapters in deterministic order
and returns an internal `FraudScoringOrchestrationResult` containing collected `FraudEngineResult`
entries.

This is internal only to `fraud-scoring-service`. It is not an event schema, not an API DTO, not a
UI model, not an alert projection, not a production runtime migration, and not a final decision
source. FDP-89 does not change `TransactionScoredEvent`, does not add `engineResults[]`, and does
not replace `CompositeFraudScoringEngine`.

## Execution Model

The orchestrator composes existing `RuleBasedSignalEngine` and `PythonMlSignalEngine` adapters. It
does not redefine adapters, does not call concrete production scoring engines directly, does not add
rule thresholds, does not add ML thresholds, and does not reinterpret engine scores or risk levels.

Execution order is deterministic:

1. `rules.primary`
2. `ml.python.primary`

The registry is explicit. FDP-89 does not rely on Spring bean order, reflection, classpath discovery,
`HashMap` order, or `Set` order. Unknown engine IDs fail fast in v1 so the ordered surface stays
reviewable.

FDP-89 has no async/executor/cancellation in FDP-89. There is no thread pool, scheduler, kill switch,
or orchestrator-level timeout policy. Adapter-returned `TIMEOUT`, `UNAVAILABLE`, and `DEGRADED`
statuses are preserved.

## Failure Boundary

Engine exceptions are converted to bounded per-engine failure result entries. Null engine results are
also converted to bounded per-engine failure result entries. Other engine results are not dropped.

No raw exception messages, no stack traces, no raw ML/rules payload, no raw feature values, no request
or response body, no endpoint, no host, no token, and no secret are exposed in orchestration results
or warnings. Unavailable is not low risk. Timeout is not low risk. FDP-89 adds no approve/decline,
no recommendedAction, no finalDecision, no final risk, and no final score.

Required/optional metadata from `FraudEngineDescriptor` is used only for bounded internal execution
warnings such as required or optional engine not available. These warnings are not external contracts
and do not decide payment, alert, or analyst workflow outcomes.

## Runtime Isolation

`FraudScoringOrchestrator` is not Spring-managed, is not wired into `CompositeFraudScoringEngine`,
does not publish Kafka events, does not call alert-service, does not call API/UI, and does not mutate
`ScoringContext`.

No Kafka event schema change, no alert-service projection, no API/UI, no analyst console, no feedback
workflow, no final decisioning, and no production migration are included in FDP-89.

## Out Of Scope

Out of scope for FDP-89:

- Kafka event extension
- alert-service projection
- API/UI
- analyst console
- feedback workflow
- final decisioning
- production migration
- engine comparison semantics beyond basic ordered collection
- public `engineResults[]`

## Next

FDP-90 may harden internal comparison/aggregation semantics. FDP-91 may extend Kafka event with
`engineResults[]` only after internal semantics are stable.
