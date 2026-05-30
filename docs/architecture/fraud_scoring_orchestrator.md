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
reviewable. FDP-89 requires both `rules.primary` and `ml.python.primary`; missing `rules.primary`
fails with `ENGINE_REGISTRY_REQUIRED_ENGINE_MISSING`, and missing `ml.python.primary` fails with
`ENGINE_REGISTRY_EXPECTED_ENGINE_MISSING`.

`FraudScoringOrchestrationResult` has an internal-only `FraudScoringOrchestrationStatus`:
`COMPLETE`, `PARTIAL`, or `REQUIRED_ENGINE_FAILED`. The status summarizes adapter availability for
future internal callers only. It does not approve, decline, calculate final risk, calculate final
score, publish events, or change runtime scoring behavior.

## Timeout Boundary

FDP-89 does not enforce engine execution deadlines.

FDP-89 has no executor, thread pool, async execution, cancellation, scheduler, kill switch, or
orchestrator-level timeout. A hanging engine can still block the caller in FDP-89. FDP-89 only
preserves `TIMEOUT` statuses returned by adapters. Real timeout enforcement belongs to FDP-90 or a
later runtime-hardening branch. Do not claim production resilience from FDP-89.

## Failure Boundary

Engine exceptions are converted to bounded per-engine failure result entries. Null engine results are
also converted to bounded per-engine failure result entries. Other engine results are not dropped.

No raw exception messages, no stack traces, no raw ML/rules payload, no raw feature values, no request
or response body, no endpoint, no host, no token, and no secret are exposed in orchestration results
or warnings. Unavailable is not low risk. Timeout is not low risk. FDP-89 adds no approve/decline,
no recommendedAction, no finalDecision, no final risk, and no final score.

Required/optional metadata from `FraudEngineDescriptor` is used only for bounded internal execution
warnings and internal orchestration status. These warnings and status values are not external
contracts and do not decide payment, alert, or analyst workflow outcomes.

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
