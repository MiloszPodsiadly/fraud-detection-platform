# Engine Intelligence Alert-Service Projection

## Purpose

Alert-service projects bounded engine intelligence into a Mongo read model. Bounded API and Analyst Console
consumption are separate read boundaries over that projection.

## Scope

The `TransactionMonitoringService` keeps the existing base scored-transaction save path and invokes an optional
internal projection after that save succeeds. The projection reads the optional public `engineIntelligence` event
field and writes a bounded Mongo read model. Internal evidence capture also routes optional validated
`mlPredictionEvidence` to a
separate private evidence collection; it does not add that exact score to the public read model.

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
diagnostic signals, bounded warnings, counts, projection timestamps, and the bounded ML model identity when it is
present on the `ml.python.primary` engine result.

The private `ml_prediction_evidence_projections` collection stores one immutable occurrence per source event ID. It
preserves transaction and correlation ownership, original event time, canonical engine ID and status, exact bounded
ML score and risk, complete model/feature-contract identity, source execution timestamp, and projection time. Source
timestamps use canonical UTC text so Mongo date precision cannot truncate the authoritative fractional value.

## Projection Policy and Limits

The alert-service projection policy reconstructs a safe copy through the shared bounded public event contract
before persistence. It enforces at most 3 engines, 5 diagnostic signals, 10 warnings, 5 reason codes per engine, and
128 characters per bounded string. Only allowlisted engine IDs, statuses, score buckets, warning codes, and public
reason codes are persisted.

Alert-service revalidates reason codes by reconstructing public DTOs rather than maintaining a second
source-of-truth allowlist. Alert-service projection revalidates public contract values before persistence. It
does not maintain a divergent second source of truth for public enum allowlists. Storage-specific limits are
enforced by `EngineIntelligenceProjectionPolicy`.

## Old Event Compatibility

Old events without engineIntelligence remain compatible. They create no engine-intelligence projection document.
Events without `mlPredictionEvidence` create no private evidence document and never erase accepted evidence.

## New Bounded Event Projection

Valid events with the optional public field create an internal read model. Operational engine results and signals
remain nullable for risk level; projection does not invent a fake risk level.
The projection stores ML model identity only from the nested Engine Intelligence ML engine result and never
reconstructs it from top-level final-scoring `modelName` or `modelVersion`.

## Invalid/Oversized Safe Omission

Unsupported contract versions and invalid or oversized shapes are omitted with bounded internal reasons. Raw
payloads and exception messages are not logged.

## Idempotency/Replay Safety

Projection must be idempotent under replay. A stable transaction ID replaces the existing Mongo document instead of
appending engines, diagnostic signals, or warnings.

The private evidence projection has stricter occurrence semantics. It uses insert-only persistence keyed by source
event ID. An identical replay is idempotent; a conflicting replay is observable and cannot overwrite accepted
evidence. Concurrent duplicate delivery produces one immutable document. A different source event ID is a distinct
scoring occurrence, even for the same transaction. Replay classification reads the authoritative stored document;
there is no read-then-save update path.

## Mongo projection identity and idempotency

Engine-intelligence projection uses transactionId as Mongo `_id`.
Mongo `_id` uniqueness is the idempotency boundary for the public projection.
Reprocessing the same transaction replaces the projection state instead of appending duplicate
engines/signals/warnings. No separate migration is required for this document-style projection unless deployment
policy requires explicit collection/index creation. Future hardening may add secondary indexes or retention/TTL
based on query and retention needs.

## Operational storage hardening

The public projection uses transactionId as Mongo `_id` for idempotent replacement.
Mongo `_id` uniqueness is the idempotency boundary.
The public projection does not add query-optimized secondary indexes.
It does not add TTL or retention policy.
Projection growth is expected to be roughly one document per scored transaction with engineIntelligence.
Before broader producer rollout, define:
- a retention policy;
- a TTL or archival strategy;
- whether retention matches scored transactions;
- whether projection is cleaned up with scored transaction;
- secondary indexes based on read/query patterns;
- storage growth monitoring.

Storage monitoring must not use high-cardinality labels such as transactionId, customerId, accountId, merchantId,
raw exception, endpoint, or payload.

## No Raw/Internal Storage

The public projection stores only bounded public event contract fields. The dedicated internal evidence collection stores
only canonical `MlPredictionEvidenceV1` and bounded source ownership fields. Raw model requests/responses, raw
features, raw contributions, arbitrary metadata, customer/account data, endpoints, tokens, secrets, stack traces,
exception messages, and internal aggregation objects must not be stored.

## API/UI Boundary

Bounded API/UI exposure exists through later scoped Engine Intelligence work. The projection must still not leak Mongo
metadata, raw payloads, internal aggregation objects, raw engine outputs, or scoring internals. API/UI layers consume
dedicated read DTOs and validators rather than the projection class directly.

The exact evidence collection has no controller, public read DTO, feedback-record field, dataset-export
field, or Analyst Console path. Public surfaces continue to expose only bounded score buckets and approved model
identity. Evidence projection failures are reported through bounded low-cardinality metrics and logs and remain
isolated from the base scored-transaction save and alert processing.

Current ownership ends at passive internal evidence capture. Governed evaluation, dataset use, calibration, model
promotion, threshold changes, and production decision authority require a separate design and review.

## No Decisioning

Projected Rules-vs-ML disagreement, unavailable engines, warnings, and diagnostic risk levels remain internal diagnostics.
They do not change alert severity, priority, recommendation, fraud-case status, assignment, escalation, or payment
authorization.

## Failure Isolation Ownership

`EngineIntelligenceProjectionService` owns normal projection failure isolation and returns bounded omission results.
`TransactionMonitoringService` retains last-resort containment so unexpected projection wiring failures cannot break
the base scored-transaction projection.

## Operational Observability

Both projection paths record low-cardinality counters and latency through the existing `AlertServiceMetrics` boundary.
Export and retention remain responsibilities of the configured Micrometer backend. The public summary projection
records:

- `engine_intelligence_projection_attempt_total`
- `engine_intelligence_projection_success_total`
- `engine_intelligence_projection_omitted_total{reason=bounded_reason}`
- `engine_intelligence_projection_latency_seconds`

The private evidence projection records corresponding
`ml_prediction_evidence_projection_*` attempts, successes, idempotent replays, bounded omissions/failures, and
latency.

Allowed labels are bounded result/reason values owned by code. Forbidden labels include
transactionId, customerId, accountId, cardId, merchantId, endpoint, payload, raw exception, and raw reason code if
unbounded. Metrics must never affect base projection.

## API Read Model Gate

The projection originally required separate review before API/UI exposure. The current bounded API, OpenAPI, and UI
contracts satisfy that gate. The guard remains useful as a checklist for any future read-model
change. API read-model tests must prove:
- a bounded response DTO;
- no raw/internal projection leakage;
- no final decisioning fields;
- old cases without projection remain compatible;
- authorization boundaries;
- no high-cardinality/raw values;
- timeout/unavailable/degraded status semantics remain safe.
