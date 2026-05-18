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

## Out Of Scope

- No UI.
- No write endpoint.
- No dismiss, confirm, or link-case action.
- No export.
- No bulk endpoint.
- No final outcome.
- No analyst workflow.
- No case lifecycle mutation.
