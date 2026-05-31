# Orchestrator Runtime Readiness Foundation

Status: FDP-90 internal runtime-hardening foundation only.

FDP-90 hardens internal orchestrator execution.
FDP-90 hardens orchestrator runtime readiness, but still does not expose engine intelligence externally.
This is an internal runtime-readiness foundation, not a production enablement claim.

FDP-90 does not wire orchestrator into `CompositeFraudScoringEngine`. There is no
`CompositeFraudScoringEngine` wiring, no `TransactionScoredEvent.engineResults[]`, no Kafka schema
changes, no alert-service projection, no API/UI, no final decisioning, and no automatic
approve/decline behavior. The internal orchestrator remains outside the live scoring path.

## Per-Engine Deadline Policy

`FraudScoringOrchestratorExecutionPolicy` contains one explicit `FraudEngineExecutionPolicy` per
allowlisted engine. Each policy contains a bounded engine ID, a positive bounded deadline, and
required/optional metadata. The internal default keeps a small deadline for `rules.primary` and a
larger deadline for `ml.python.primary`.

The policy is defensive: null entries, duplicates, missing allowlisted entries, and unbounded
deadlines fail fast with bounded messages.

## Bounded Executor Policy

`BoundedFraudEngineExecutor` owns a fixed-size thread pool backed by a bounded queue. It supports
shutdown, uses daemon worker threads with bounded idle retention, restores the caller interrupt flag,
and attempts future cancellation when a deadline expires. It does not use an unbounded queue, cached
pool, common pool, parallel stream, or global static executor.

Cancellation is cooperative.
Timeout result means orchestrator stopped waiting for the engine result; it does not guarantee the underlying work was forcibly terminated unless the engine cooperates with cancellation.

## Timeout Mapping

An orchestrator-owned timeout becomes an internal bounded `TIMEOUT` result with:

- `score = null`
- `riskLevel = null`
- `confidence = UNKNOWN`
- `statusReason = ORCHESTRATOR_ENGINE_TIMEOUT`

Timeout is not low risk. Timeout is not available. A required timeout produces
`REQUIRED_ENGINE_FAILED`. An optional timeout produces `PARTIAL`. A timeout does not erase results
already collected from other engines.

## Latency Measurement

The orchestrator receives a `Clock` and measures bounded elapsed time for each engine execution.
Timeout latency uses the bounded deadline. The measured duration is sent to the metrics abstraction
for every engine execution. For orchestrator-created failure and timeout outcomes, the same measured
duration is also written to the internal engine result. Adapter-produced results remain unchanged.

## Metrics Abstraction

`FraudScoringOrchestratorMetrics` is an internal interface. `NoOpFraudScoringOrchestratorMetrics` is
the default-safe implementation. FDP-90 adds no vendor-specific integration.

Metrics use low-cardinality labels only:

- `engine_id`
- `engine_type`
- `status`
- `required`

The engine IDs are allowlisted to `rules.primary` and `ml.python.primary`. Status labels are
allowlisted to `AVAILABLE`, `UNAVAILABLE`, `TIMEOUT`, and `DEGRADED`.

Forbidden metric labels and values:

- `transactionId` forbidden
- `customerId` forbidden
- `accountId` forbidden
- `cardId` forbidden
- `merchantId` forbidden
- raw exception forbidden
- payload forbidden
- endpoint forbidden
- token forbidden
- secret forbidden

## Runtime Isolation

Runtime isolation remains intact. FDP-90 changes internal execution readiness only. It adds no
production migration, no public engine intelligence exposure, no final decision source change, and
no event, projection, controller, or analyst-console integration.
