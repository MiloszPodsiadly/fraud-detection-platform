# SuspiciousTransaction Internal UI

Status: current product documentation for the FDP-66 internal read-only UI.

## Purpose

The SuspiciousTransaction internal UI presents the existing FDP-62 protected read API in the analyst console.
It is an internal read view for system-detected suspicious scoring signals.

The UI consumes the protected internal read contract:

- GET `/internal/suspicious-transactions/summary`
- GET `/internal/suspicious-transactions`
- GET `/internal/suspicious-transactions/{suspiciousTransactionId}`

## Product Semantics

Every list and detail view must preserve these meanings:

- System-detected suspicious signal.
- Not confirmed fraud.
- Not an analyst decision.
- Not a final outcome.
- Not a fraud case.
- Not legal proof.

The UI must not rename a SuspiciousTransaction into a fraud verdict, fraud confirmation, analyst disposition,
or case lifecycle state.

## Authorization Boundary

The frontend navigation and route guard use the existing `SUSPICIOUS_TRANSACTION_READ` authority as a UX/request gate.
The frontend guard is not a security boundary.
Backend authorization remains authoritative for all internal read endpoints.

The UI must not add roles, authorities, backend authorization rules, or bypass paths.

## List View

The list view is read-only and cursor-based.
It shows a bounded slice returned by the existing API.

Allowed list fields:

- suspiciousTransactionId
- transactionId
- customerId
- accountId
- riskScore
- riskLevel
- detectionSource
- evidenceStatus
- evidenceSnapshotItemCount
- evidenceProjectionState
- linkedAlertId as a reference only
- status
- detectedAt

The workspace navigation may show the global suspicious signal total returned by the dedicated summary endpoint.
The cursor list itself must not expose a page-scoped total, total pages, page number navigation, offset navigation,
or raw cursor values.
The only forward pagination affordance is cursor-based `Load next` when the API returns `hasNext=true`.

## Detail View

The detail view is read-only and displays the existing read-model response.

Allowed detail fields:

- identifiers: suspiciousTransactionId, transactionId, sourceEventId, correlationId, customerId, accountId
- linkedAlertId as a reference only
- riskScore and riskLevel
- detectionSource
- status
- detectedAt, createdAt, updatedAt
- scoreDecisionId
- scoringStrategy
- modelName
- modelVersion
- reasonCodes as metadata only
- evidenceStatus, evidenceSnapshotItemCount, and evidenceProjectionState as metadata only

The detail view must not display full evidence snapshots, raw payloads, raw attribute maps, exported response bodies,
or decoded cursor content.

## Out Of Scope

- No write endpoint.
- No confirm, dismiss, submit, link-case, assign, claim, export, or bulk action.
- No analyst workflow.
- No case lifecycle mutation.
- No final outcome.
- No fraud confirmation.
- No legal-proof claim.
- No frontend telemetry that records identifiers, cursor values, raw responses, tokens, or emails.
- No new backend filter.

## UI Route

The console keeps the existing workspace query routing pattern.
The SuspiciousTransaction workspace is reached through `?workspace=suspicious-transactions`.
Detail selection uses `suspiciousTransactionId` as a UI route parameter and still reads through the existing FDP-62
backend detail endpoint.

## Related Sources

- [Suspicious Transactions](suspicious_transactions.md)
- [SuspiciousTransaction Internal Read API](suspicious_transaction_read_api.md)
- [Endpoint authorization map](../security/endpoint_authorization_map.md)
