# SuspiciousTransaction Internal Read API

Status: current product documentation for FDP-62.

## Purpose

The SuspiciousTransaction Internal Read API is a protected, read-only API for the SuspiciousTransaction read model.
It exposes system-detected suspicious scoring signals for internal operational review.

## Non-Claims

- SuspiciousTransaction does not mean confirmed fraud.
- The API does not expose analyst decision.
- The API does not expose final outcome.
- The API does not mutate case lifecycle.
- The API does not expose a full evidence snapshot.
- The API does not recompute evidenceStatus.

## Endpoints

- GET /internal/suspicious-transactions
- GET /internal/suspicious-transactions/{suspiciousTransactionId}

There is no public unauthenticated endpoint for this read model.

## Authorization

Both endpoints require SUSPICIOUS_TRANSACTION_READ.
This authority is not granted to the read-only viewer role because the response includes customerId and accountId.
It is granted to analyst, reviewer, and fraud-ops administrator roles.

## Pagination And Filters

- size defaults to 20.
- size max is 100.
- cursor is optional and opaque.
- page and sort parameters are rejected.
- filters are exact-match or bounded range filters.
- regex search is not supported.
- raw query passthrough is not supported.

## Search Pagination Semantics

SuspiciousTransaction search uses bounded cursor/keyset slice pagination.

The response includes:

- content
- size
- hasNext
- nextCursor

The response does not include:

- page
- totalElements
- totalPages
- totalCount
- offset

The endpoint does not use:

- global count query
- offset skip
- unbounded list
- raw query passthrough
- regex search

Reason:
A full count over suspicious_transactions can be expensive for broad or empty filters. FDP-62 avoids unbounded count scans
by fetching at most size + 1 records with keyset predicates.

Clients must navigate using nextCursor and must not rely on page number or total page count.

Rules:

- default size = 20.
- max size = 100.
- fetch limit = size + 1.
- hasNext indicates whether another cursor slice exists.
- nextCursor is present only when hasNext is true.
- no total collection count is computed.
- no page number navigation is supported.
- no offset skip is used.
- no raw query passthrough.
- no regex search.

Unfiltered search:

Allowed only as bounded cursor slice returning the most recent suspicious transactions first.
It does not compute total count.

Allowed filters:

- status
- riskLevel
- customerId
- linkedAlertId
- detectedFrom
- detectedTo

Sort order:

- detectedAt DESC
- suspiciousTransactionId DESC

## Mongo Index Support

The suspicious_transactions collection declares explicit Mongo indexes for the FDP-62 cursor/keyset read patterns.
These indexes are performance support only: they support the current primary access paths, but this does not change API behavior,
response fields, authorization, projection semantics, cursor semantics, or product workflow.
FDP-63 verifies declared index definitions and, where integration proof is available, verifies that Spring Data creates
the expected indexes in Mongo through runtime indexInfo inspection.

Declared read indexes:

| Index | Key pattern | Supported access path |
| --- | --- | --- |
| idx_suspicious_tx_cursor_detected_at_id_desc | detectedAt DESC, _id DESC | Unfiltered cursor reads ordered by detectedAt and the Mongo ID tie-breaker |
| idx_suspicious_tx_status_cursor | status ASC, detectedAt DESC, _id DESC | status filter with cursor ordering |
| idx_suspicious_tx_risk_cursor | riskLevel ASC, detectedAt DESC, _id DESC | riskLevel filter with cursor ordering |
| idx_suspicious_tx_customer_cursor | customerId ASC, detectedAt DESC, _id DESC | customerId filter with cursor ordering |
| idx_suspicious_tx_alert_cursor | linkedAlertId ASC, detectedAt DESC, _id DESC | linkedAlertId filter with cursor ordering |
| suspicious_transaction_source_event_unique_idx | transactionId ASC, sourceEventId ASC | Idempotency for projection writes |

The API field remains suspiciousTransactionId. It maps to Mongo _id because suspiciousTransactionId is the document @Id.
Cursor indexes use _id as the physical tie-breaker field.

Combination indexes are intentionally not declared for multi-filter permutations such as status plus riskLevel,
status plus customerId, customerId plus riskLevel, status plus linkedAlertId, accountId, correlationId, or reasonCodes.
New combinations require observed production telemetry and an explicit index decision.

The linkedAlertId read path uses a normal compound index. A partial index can be evaluated later if telemetry shows that
null-heavy index cost is material for this collection.

These indexes are not a security control. They are not confirmed-fraud evidence, audit assurance, or legal or regulatory
evidence, and they do not guarantee latency under every data distribution.
Mongo query planner behavior can depend on Mongo version, data distribution, and selectivity.

## Response Semantics

The response preserves SuspiciousTransaction read-model fields. It does not rename a suspicious signal into fraud.
It does not recompute evidenceStatus and does not expose evidenceSnapshot.

## Audit

Read and search requests are audited with a bounded filter summary. Audit records do not store full response bodies,
raw payloads, raw exception messages, or reason-code lists.

## Metrics

API metrics use low-cardinality labels only:

- outcome
- status
- riskLevel

Metrics do not include transactionId, suspiciousTransactionId, customerId, accountId, sourceEventId, correlationId,
linkedAlertId, raw filters, raw exception messages, reasonCodes, or modelName.

## Query Telemetry

SuspiciousTransaction search and single-read paths also emit low-cardinality query telemetry for the existing internal
read API. The telemetry measures query shape and response shape so production observations can inform future index decisions.
It does not change API behavior, does not add filters, does not add indexes, and does not guarantee latency.

The query telemetry uses a timer histogram named `fraud.suspicious_transaction.api.query` with bounded dimensions:

- endpoint: search or read.
- outcome: success, not_found, validation_error, forbidden, or error.
- query shape: id_lookup, unfiltered, status, risk, customer, linked_alert, date_range, multi_filter, or unknown.
- filter count bucket: 0, 1, 2, or 3_plus.
- result size bucket: 0, 1_10, 11_50, 51_100, or unknown.
- hasNext: true, false, or unknown.
- cursorUsed: true, false, or unknown. It indicates whether a request used cursor pagination and does not contain or
  derive the cursor token value.

Telemetry label values are normalized at the telemetry boundary even if a caller bypasses the classifier. Unknown values
fall back to bounded labels before metrics or logs are emitted.

Duration is recorded by the Micrometer Timer/histogram. The durationBucket value is used for bounded slow-query logs and
diagnostics, not as a high-cardinality metric label. Allowed duration buckets are lt_50ms, 50_100ms, 100_250ms,
250_500ms, or 500ms_plus.

Telemetry does not record raw identifiers, cursor token values, decoded cursor values, reason-code lists, model fields,
raw filters, raw query text, raw exception messages, or response bodies. It must not become a data-extraction channel.

Production telemetry wiring is required. Runtime telemetry recording failures do not alter API responses, but missing telemetry beans must not silently disable telemetry in production wiring.

Slow query warning logs use a default threshold of 500ms and include only endpoint, outcome, query shape, filter count
bucket, result size bucket, hasNext, cursorUsed, and duration bucket. They do not include raw identifiers, cursor tokens,
decoded cursor values, raw filters, raw query text, raw exception messages, stack traces for expected validation or
not-found outcomes, response bodies, or exact result counts.

The telemetry is diagnostic. It informs future index decisions, but it does not guarantee performance. It is not a
security control, not audit assurance, not fraud proof, and not legal or regulatory evidence.

## Out Of Scope

- No UI.
- No write endpoint.
- No dismiss, confirm, or link-case action.
- No export.
- No bulk endpoint.
- No final outcome.
- No analyst workflow.
- No case lifecycle mutation.
