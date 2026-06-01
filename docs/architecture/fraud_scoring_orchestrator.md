# Internal Fraud Scoring Orchestrator

Status: FDP-89 internal-only orchestration foundation with FDP-90 internal runtime hardening.

## Purpose

`FraudScoringOrchestrator` executes isolated `FraudSignalEngine` adapters in deterministic order
and returns an internal `FraudScoringOrchestrationResult` containing collected `FraudEngineResult`
entries.

This is internal only to `fraud-scoring-service`. It is not an event schema, not an API DTO, not a
UI model, not an alert projection, not a production runtime migration, and not a final decision
source. FDP-89 does not change `TransactionScoredEvent`, does not add `engineResults[]`, and does not
replace `CompositeFraudScoringEngine`. FDP-90 preserves the same external boundary.

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

## Registry Boundary

FDP-89 registry is intentionally strict. FDP-89 requires `rules.primary` and `ml.python.primary`.
Missing `rules.primary` is deployment/construction failure. Missing `ml.python.primary` is FDP-89
composition failure. Missing `rules.primary` fails with `ENGINE_REGISTRY_REQUIRED_ENGINE_MISSING`,
and missing `ml.python.primary` fails with `ENGINE_REGISTRY_EXPECTED_ENGINE_MISSING`.

Runtime ML unavailable is different from missing ML registration. ML unavailable can produce
`PARTIAL`. Missing ML registration fails registry construction. FDP-89 is not a general-purpose
plugin system. Future dynamic engine discovery belongs to a later branch.

`FraudScoringOrchestrationResult` has an internal-only `FraudScoringOrchestrationStatus`:
`COMPLETE`, `PARTIAL`, or `REQUIRED_ENGINE_FAILED`. The status summarizes adapter availability for
future internal callers only. It does not approve, decline, calculate final risk, calculate final
score, publish events, or change runtime scoring behavior.

`COMPLETE` is not a safe/final outcome. `PARTIAL` is not low risk. `REQUIRED_ENGINE_FAILED` is not
decline/block. Status is internal execution completeness only. Final decisioning belongs to future
explicit decision policy, not FDP-89.

## Timeout Boundary

FDP-89 introduced the synchronous baseline without deadline enforcement. FDP-90 adds an internal
per-engine deadline policy and bounded executor policy. The explicit policy uses a small bounded
deadline for `rules.primary` and a larger bounded deadline for `ml.python.primary`.

When the bounded deadline expires, FDP-90 maps the engine execution to a bounded `TIMEOUT` result.
Timeout is not low risk. Timeout is not available. A required timeout produces
`REQUIRED_ENGINE_FAILED`; an optional timeout produces `PARTIAL`. Other engine results remain
available to the internal orchestration result.

Cancellation is cooperative. Timeout result means orchestrator stopped waiting for the engine
result; it does not guarantee the underlying work was forcibly terminated unless the engine
cooperates with cancellation.

FDP-90 measures bounded per-engine latency through an injected clock and records the same measured
duration through an internal metrics abstraction. The default implementation is
`NoOpFraudScoringOrchestratorMetrics`; FDP-90 adds no vendor-specific metrics integration.

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

`FraudScoringExecutionWarning` values are structured internal execution warnings containing bounded
engine ID, warning code, engine status, and required/optional context. They are not event fields, API
DTOs, UI model fields, alert projections, or decision policy inputs.

## Evidence Type Limitation

FDP-89 uses existing `FraudEngineEvidenceType.OPERATIONAL_FALLBACK` for bounded orchestration failure
evidence. This does not mean the orchestrator performed business fallback decisioning. It only
indicates an operational engine execution failure was represented safely. A dedicated `ENGINE_STATUS`
or `ORCHESTRATION_STATUS` evidence type may be considered in a future common-events hardening branch.
FDP-89 does not change common-events evidence taxonomy.

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

## Runtime Readiness Detail

See [Orchestrator runtime readiness foundation](orchestrator_runtime_readiness.md) for FDP-90 policy,
executor, latency, metrics, and runtime-isolation details.

## Next

FDP-91 adds internal-only bounded aggregation and comparison semantics without wiring the
orchestrator into production scoring and without adding `TransactionScoredEvent.engineResults[]`.
See [Internal engine result aggregation and comparison](internal_engine_result_aggregation.md).

Any future Kafka event extension belongs to a separate compatibility-reviewed branch after internal
semantics are stable.
