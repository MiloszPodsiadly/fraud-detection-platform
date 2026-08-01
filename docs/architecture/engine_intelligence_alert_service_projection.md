# Engine Intelligence Alert-Service Projection

## Purpose

Alert-service projects bounded engine intelligence into a Mongo read model. Historical FDP-95 introduced the
projection-only slice; later scoped work added bounded API and Analyst Console consumption.

## Scope

The `TransactionMonitoringService` keeps the existing base scored-transaction save path and invokes an optional
internal projection after that save succeeds. The projection reads the optional public `engineIntelligence` event
field and writes a bounded Mongo read model.

## Current Scope

The projection is the alert-service storage boundary for public `TransactionScoredEvent.engineIntelligence`.
Current API/UI exposure is owned by the bounded Engine Intelligence API read model, OpenAPI contract, and Analyst
Console validators. This projection document does not grant new scoring, workflow, or authorization behavior.

## Non-goals

The projection does not use engine intelligence for decisions. Final decisioning remains out of scope.

## Projection-only Boundary

The internal projection does not call ML, rules, scoring, alert-management, fraud-case, or payment-authorization
logic. Projection failure must not break base alert projection.

## Storage Model

The `engine_intelligence_projections` Mongo collection stores one replacement document per transaction ID. The
document contains the contract version, generated timestamp, explicit Rules-vs-ML comparison identity and summary, bounded engine results, bounded
diagnostic signals, bounded warnings, counts, and projection timestamps.

## Projection Policy and Limits

The alert-service projection policy reconstructs a safe copy through the shared bounded public event contract
before persistence. It enforces at most 3 engines, 5 diagnostic signals, 10 warnings, 5 reason codes per engine, and
128 characters per bounded string. Only allowlisted engine IDs, statuses, score buckets, warning codes, and public
reason codes are persisted.

Alert-service revalidates reason codes by reconstructing FDP-92 public DTOs rather than maintaining a second
source-of-truth allowlist. Alert-service projection revalidates public contract values before persistence. FDP-95
does not maintain a divergent second source of truth for public enum allowlists. Storage-specific limits are
enforced by `EngineIntelligenceProjectionPolicy`.

## Old Event Compatibility

Old events without engineIntelligence remain compatible. They create no engine-intelligence projection document.

## New Bounded Event Projection

Valid events with the optional public field create an internal read model. Operational engine results and signals
remain nullable for risk level; projection does not invent a fake risk level.

## Invalid/Oversized Safe Omission

Unsupported contract versions and invalid or oversized shapes are omitted with bounded internal reasons. Raw
payloads and exception messages are not logged.

## Idempotency/Replay Safety

Projection must be idempotent under replay. A stable transaction ID replaces the existing Mongo document instead of
appending engines, diagnostic signals, or warnings.

## Mongo projection identity and idempotency

Engine-intelligence projection uses transactionId as Mongo `_id`.
Mongo `_id` uniqueness is the idempotency boundary for FDP-95.
Reprocessing the same transaction replaces the projection state instead of appending duplicate
engines/signals/warnings. No separate migration is required for this document-style projection unless deployment
policy requires explicit collection/index creation. Future hardening may add secondary indexes or retention/TTL
based on query and retention needs.

## Operational storage hardening

FDP-95 uses transactionId as Mongo `_id` for idempotent replacement.
Mongo `_id` uniqueness is the idempotency boundary.
FDP-95 does not add query-optimized secondary indexes.
FDP-95 does not add TTL or retention policy.
Projection growth is expected to be roughly one document per scored transaction with engineIntelligence.
Before the FDP-96 API read model or broader producer rollout, define:
- a retention policy;
- a TTL or archival strategy;
- whether retention matches scored transactions;
- whether projection is cleaned up with scored transaction;
- secondary indexes based on read/query patterns;
- storage growth monitoring.

Storage monitoring must not use high-cardinality labels such as transactionId, customerId, accountId, merchantId,
raw exception, endpoint, or payload.

## No Raw/Internal Storage

Only bounded public event contract fields may be stored. Raw evidence, raw contributions, feature vectors,
endpoints, tokens, secrets, stack traces, exception messages, and internal aggregation objects must not be stored.

## API/UI Boundary

Bounded API/UI exposure exists through later scoped Engine Intelligence work. The projection must still not leak Mongo
metadata, raw payloads, internal aggregation objects, raw engine outputs, or scoring internals. API/UI layers consume
dedicated read DTOs and validators rather than the projection class directly.

## No Decisioning

Projected Rules-vs-ML disagreement, unavailable engines, warnings, and diagnostic risk levels remain internal diagnostics.
They do not change alert severity, priority, recommendation, fraud-case status, assignment, escalation, or payment
authorization.

## Failure Isolation Ownership

`EngineIntelligenceProjectionService` owns normal projection failure isolation and returns bounded omission results.
`TransactionMonitoringService` retains last-resort containment so unexpected projection wiring failures cannot break
the base scored-transaction projection.

## Future Operational Hardening

Projection metrics are future operational hardening. FDP-95 does not add production metrics backend.
Before broad rollout, add low-cardinality metrics:

- `engine_intelligence_projection_attempt_total`
- `engine_intelligence_projection_success_total`
- `engine_intelligence_projection_omitted_total{reason=bounded_reason}`
- `engine_intelligence_projection_latency_seconds`

Allowed labels are bounded `result`, `omission_reason`, and `projection_version`. Forbidden labels include
transactionId, customerId, accountId, cardId, merchantId, endpoint, payload, raw exception, and raw reason code if
unbounded. Metrics must never affect base projection.

## Historical FDP-96 API Read Model Gate

Historical FDP-95 required separate FDP-96/FDP-97 review before API/UI exposure. That gate has been superseded by the
current bounded API, OpenAPI, and UI contracts. The guard remains useful as a checklist for any future read-model
change. API read-model tests must prove:
- a bounded response DTO;
- no raw/internal projection leakage;
- no final decisioning fields;
- old cases without projection remain compatible;
- authorization boundaries;
- no high-cardinality/raw values;
- timeout/unavailable/degraded status semantics remain safe.
