# SuspiciousTransaction Internal UI

Status: current product documentation for the FDP-66 internal read-only UI.

## Purpose

The SuspiciousTransaction internal UI presents the existing FDP-62 protected read API in the analyst console.
It is an internal read view for system-detected suspicious scoring signals.

The UI consumes the protected internal read contract:

- GET `/internal/suspicious-transactions/summary`
- GET `/internal/suspicious-transactions`
- GET `/internal/suspicious-transactions/{suspiciousTransactionId}`

Accepted FDP-66 scope is limited to:

- UI read-only list and detail views.
- The existing cursor list and detail API.
- One additive backend API change: GET `/internal/suspicious-transactions/summary` for the workspace aggregate counter.

Only additive backend API change allowed in FDP-66 is GET /internal/suspicious-transactions/summary for workspace aggregate counter.

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
Navigation visibility is driven by `SUSPICIOUS_TRANSACTION_READ`.
Frontend capability mapping is a session and UX hint only; backend authorization is authoritative.
FDP-66 does not change production role provisioning.
Demo role authority mapping affects only local analyst-console demo/session behavior.

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

The workspace navigation may show the workspace suspicious signal total returned by the dedicated summary endpoint.
The backend field name is `totalSuspiciousTransactions`; visible UI copy uses system-signal language such as
`Suspicious signals`, `System signal total`, or `Workspace signal total`.
The cursor list itself must not expose a page-scoped total, total pages, page number navigation, offset navigation,
or raw cursor values.
The only forward pagination affordance is cursor-based `Load next` when the API returns `hasNext=true`.

## Summary Counter Semantics

GET `/internal/suspicious-transactions/summary` is read-only, requires `SUSPICIOUS_TRANSACTION_READ`, and is audited as
an aggregate read. It returns `totalSuspiciousTransactions` as the workspace-level aggregate counter plus cache freshness
metadata (`freshness`, `cachedAt`, and `expiresAt`).

The summary total is cached or materialized for the workspace counter and uses a configurable TTL. The default runtime
TTL is 30 seconds. A fresh cached value is reused within the TTL so repeated workspace refreshes do not trigger a global
collection count. If a refresh fails after a cached value exists, the endpoint returns the stale cached value with
`freshness=STALE`. If no cached value exists and the refresh fails, the endpoint returns an unavailable summary with
`freshness=UNAVAILABLE` and a zero total for safe UI rendering. The summary endpoint must not execute a global collection count on every request.

The summary endpoint is aggregate only. It is not pagination total metadata, not page count, not total pages, not a final outcome,
not a confirmed fraud count, not analyst workload, and not a fraud-case count. It must not be used for offset navigation,
page-number navigation, or deciding whether the cursor list has another slice.

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

Negated safety disclaimers such as `Not confirmed fraud`, `Not an analyst decision`, `Not a final outcome`, `Not legal
proof`, and `No case lifecycle mutation` are allowed. Affirmative workflow, fraud-verdict, case-linking, export, bulk,
or final-decision wording is forbidden.

## UI Route

The console keeps the existing workspace query routing pattern.
The SuspiciousTransaction workspace is reached through `?workspace=suspicious-transactions`.
Detail selection uses `suspiciousTransactionId` as a UI route parameter and still reads through the existing FDP-62
backend detail endpoint.

## Related Sources

- [Suspicious Transactions](suspicious_transactions.md)
- [SuspiciousTransaction Internal Read API](suspicious_transaction_read_api.md)
- [Endpoint authorization map](../security/endpoint_authorization_map.md)
