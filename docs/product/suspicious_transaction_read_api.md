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

## Pagination And Filters

- page must be greater than or equal to 0.
- size defaults to 20.
- size max is 100.
- sort uses an allowlist only.
- filters are exact-match or bounded range filters.
- regex search is not supported.
- raw query passthrough is not supported.

Allowed filters:

- status
- riskLevel
- customerId
- linkedAlertId
- detectedFrom
- detectedTo

## Sort Allowlist

- detectedAt
- updatedAt
- riskScore
- riskLevel
- status

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
