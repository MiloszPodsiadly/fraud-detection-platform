# Engine Intelligence Alert-Service Projection

## Purpose

Alert-service projects bounded engine intelligence into a Mongo read model. Bounded API and Analyst Console
consumption are separate read boundaries over that projection.

## Scope

The `TransactionMonitoringService` writes the base scored-transaction projection and invokes an optional
internal projection after that write succeeds. The projection reads the optional public `engineIntelligence` event
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

### Scoring occurrence ownership

The base `scored_transactions` document privately preserves the authoritative scored event ID and its exact event
timestamp. One scoring occurrence is one immutable `TransactionScoredEvent.eventId`. Replaying that ID cannot mutate
the accepted transaction state. For distinct event IDs concerning the same transaction, the later event `createdAt`
wins; equal timestamps use the lexicographically greater event ID as the deterministic tie breaker. Exact epoch second
and nanosecond components are persisted so ordering does not depend on Mongo date precision or delivery order.

Selection is an atomic conditional write. Concurrent and out-of-order deliveries therefore converge on the same
occurrence without read-then-save behavior. A historical document missing any occurrence identity component maps to
`UNKNOWN_OCCURRENCE`; identity is never reconstructed from transaction ID, processing time, model registry state, or
Mongo natural order. The first valid current event may replace that unknown state.

Event time is producer-owned ordering, so producer clock skew can delay or prevent a later real-world execution from
replacing a future-dated occurrence. The consumer does not silently substitute processing time because that would make
replay results delivery-dependent. Producer clock health and future explicit sequence contracts are operational
concerns; the stored source event ID remains the exact join key to immutable ML evidence.

Occurrence ownership is private persistence/domain metadata. It is intentionally absent from scored-transaction API
DTOs. It establishes a reliable future feedback join but does not itself snapshot evidence or change alert decisions.

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

Evidence capture consumes the scored-event topic through its own consumer group and record-level acknowledgement.
Transient store and unknown infrastructure failures retain the Kafka delivery for bounded retry. Permanently invalid
evidence, unsupported wire shapes, corrupt stored documents, and conflicting replays are quarantined without being
converted into accepted evidence. After bounded retries, handoff to the durable dead-letter topic must complete
successfully before the source record is acknowledged; failed handoff remains a consumer failure. Replaying a
quarantined or previously interrupted valid source event invokes only the evidence projection consumer, not baseline
transaction, alert, or audit business processing.

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

## Private evidence operational controls

Exact ML score and model identity are restricted to the internal scored-event topic and the private immutable evidence
collection. They are absent from logs, exception messages, metric labels, public read DTOs, and the Analyst Console.
Kafka authorization must grant scored-event read access to the baseline and evidence consumer service identities only;
the evidence group requires write access to its dead-letter topic, while unrelated clients receive no evidence-topic
read grant. Mongo credentials for the evidence collection are limited to the projection writer and explicitly approved
governance readers. Application roles and public API credentials do not grant collection access.

Broad rollout requires an approved retention and archival period long enough to cover associated evaluation and
governance artifacts. No TTL is configured because uncoordinated expiry could destroy required evidence. Operations
must monitor consumer lag/recovery backlog, persistent projection failures and dead-letter growth, collection size and
storage capacity, and replay-conflict/corrupt-document rates. Alerts must be low-cardinality and must not contain exact
scores, model identities, source event IDs, transaction IDs, payloads, or exception text. Immutable records are
insert-only by source event ID; archival and deletion require a separately governed process.

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
