# Engine Intelligence Alert-Service Projection

## Purpose

Alert-service projects bounded engine intelligence into a Mongo read model. Bounded API and Analyst Console
consumption are separate read boundaries over that projection.

## Scope

The baseline scored-event consumer writes the authoritative scored transaction and alert state in its MongoDB
transaction. A separate `engine-intelligence` Kafka consumer group reads the same event and projects the optional
public `engineIntelligence` field into a bounded Mongo read model. Internal evidence capture also routes optional validated
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

The `engine_intelligence_projections` Mongo collection stores at most one replacement document per transaction ID.
The document privately records its authoritative `sourceEventId` owner together with the contract version, generated
timestamp, explicit Rules-vs-ML comparison identity and summary, bounded engine results, bounded
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

This is temporary deployment compatibility for fully identity-free historical Mongo documents, not a second current
runtime path. Current domain construction and writes require explicit authoritative ownership; partial identity fails
closed. The inventory, archival procedure, and verifiable removal gate are defined in
[Scoring Occurrence Ownership Migration](scoring_occurrence_ownership_migration.md).

Event time is producer-owned ordering, so producer clock skew can delay or prevent a later real-world execution from
replacing a future-dated occurrence. The consumer does not silently substitute processing time because that would make
replay results delivery-dependent. Producer clock health and future explicit sequence contracts are operational
concerns; the stored source event ID remains the exact join key to immutable ML evidence.

Occurrence ownership is private persistence/domain metadata. It is intentionally absent from scored-transaction API
DTOs. It establishes a reliable future feedback join but does not itself snapshot evidence or change alert decisions.

Alert creation and its `fraud.alerts` publication intent commit in the same MongoDB transaction. The scheduled
publisher claims pending intents with a bounded lease and records `PUBLISH_ATTEMPTED` before contacting Kafka. An
expired pre-publication claim is safe to resume; an expired post-attempt lease becomes
`PUBLISH_CONFIRMATION_UNKNOWN` and is never published automatically because delivery may already have succeeded.
Operators must reconcile that explicit ambiguous state instead of treating it as a normal retry.

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
A newer authoritative occurrence without diagnostics never inherits an older occurrence's projection: snapshot reads
return `NOT_PROJECTED` unless the private projection owner matches the current scored transaction.
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
Transient store and unknown infrastructure failures retain the Kafka delivery for bounded retry. Exhausted transient
failures go to the private evidence DLT; permanently invalid evidence, unsupported wire shapes, corrupt stored
documents, and conflicting replays go to the private terminal quarantine. Handoff must complete successfully before
the source record is acknowledged; failed handoff remains a consumer failure. Controlled recovery copies selected DLT
records, with original source headers intact, to the evidence-only redrive topic. That consumer delegates to the same
canonical projection service and cannot activate baseline transaction, alert, fraud-case, or audit processing. The
redrive listener is disabled by default and its failures return to DLT or quarantine rather than looping automatically.
The operational procedure and retention boundary are defined in
[ML Prediction Evidence Recovery](../runbooks/ml_prediction_evidence_recovery.md).

## Mongo projection identity and idempotency

Engine-intelligence projection uses transactionId as Mongo `_id` and the source event ID, exact creation time, and
canonical fingerprint as its private occurrence fence. Mongo `_id` uniqueness prevents duplicate public projections,
while the complete occurrence fence prevents stale or conflicting replay from overwriting current diagnostics.
The exact creation time is persisted as canonical timestamp text plus epoch second and nanosecond components, so two
occurrences inside one Mongo millisecond retain their producer order.
Reprocessing the same occurrence replaces the projection state instead of appending duplicate
engines/signals/warnings. Existing projection documents with all occurrence identity components absent remain
readable from Mongo but fail closed as `NOT_PROJECTED` for current model-specific interpretation. Partially populated
identity is invalid and fails closed as projection unavailable; no timestamp precision is fabricated. No separate
migration is required for this document-style projection unless deployment
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
Kafka authorization must grant scored-event read access to the baseline and evidence consumer service identities only.
The evidence group requires write access to its DLT and quarantine topics. A separate, time-bounded recovery identity
may read the DLT and write redrive, while unrelated clients receive no evidence-topic read grant. Mongo credentials for
the evidence collection are limited to the projection writer and explicitly approved governance readers. Application
roles and public API credentials do not grant collection access.

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

The optional projection runs in its own Kafka consumer group and a separate MongoDB transaction. It validates the
event against the authoritative scored transaction before writing diagnostics. The write boundary also applies an
atomic Mongo occurrence fence ordered by source event timestamp and event ID, with exact fingerprint equality required
for same-event replay. Timestamp comparison uses the persisted epoch second and nanosecond before the event-ID tie
breaker. A delayed older worker therefore cannot replace a newer accepted projection. The read boundary
independently requires the source event ID, exact creation time, and canonical fingerprint to match and returns
`NOT_PROJECTED` rather than mixed-occurrence model identity.

When the baseline occurrence is not committed yet, the diagnostic listener durably stores a bounded projection-only
envelope before acknowledging Kafka. A scheduled lease-fenced worker completes the projection after the authoritative
occurrence appears. Retry and age limits move unresolved work to an observable terminal state. Other transient EI
consumer failures use bounded Kafka retry and the dedicated `engine-intelligence.dead-letter` topic; they are never
mixed with baseline `transactions.dead-letter`. A disabled-by-default `engine-intelligence.redrive` consumer admits
validated records to the same durable inbox and invokes no baseline business service; terminal redrive failures use
`engine-intelligence.quarantine`. Invalid
optional diagnostic shapes are bounded omissions or permanent EI failures. No exception is swallowed inside an aborted
MongoDB transaction, and diagnostic failure cannot roll back baseline scoring or alert processing. The operator
procedure is defined in
[Engine Intelligence Projection Recovery](../runbooks/engine_intelligence_projection_recovery.md).

## Operational Observability

Both projection paths record low-cardinality counters and latency through the existing `AlertServiceMetrics` boundary.
Export and retention remain responsibilities of the configured Micrometer backend. The public summary projection
records:

- `engine_intelligence_projection_attempt_total`
- `engine_intelligence_projection_success_total`
- `engine_intelligence_projection_omitted_total{reason=bounded_reason}`
- `engine_intelligence_projection_latency_seconds`
- `engine_intelligence_projection_disposition_total{disposition=bounded_disposition}`
- `engine_intelligence_recovery_total{outcome=bounded_outcome}`
- `engine_intelligence_pending_projection_count`
- `engine_intelligence_unresolved_projection_count`
- `engine_intelligence_oldest_pending_projection_age_seconds`

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
