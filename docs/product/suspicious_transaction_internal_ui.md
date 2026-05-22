# SuspiciousTransaction Internal UI

Status: current product documentation for the SuspiciousTransaction internal read-only UI.

## Purpose

The SuspiciousTransaction internal UI presents the existing FDP-62 protected read API in the analyst console.
It is an internal read view for system-detected suspicious scoring signals.

The UI consumes the protected internal read contract:

- GET `/internal/suspicious-transactions/summary`
- GET `/internal/suspicious-transactions`
- GET `/internal/suspicious-transactions/{suspiciousTransactionId}`
- GET `/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`

Current SuspiciousTransaction internal UI scope is limited to:

- UI read-only list and detail views.
- The cursor list, detail, summary, and linked-alert context read APIs.
- Relationship-validated linked alert context returned through the SuspiciousTransaction boundary.

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

## Linked Alert Context

The linked alert bridge opens read-only alert context when `linkedAlertId` is present and the session has alert read
authority. The backend linked-alert resolver endpoint is GET
`/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert` for relationship-validated alert context.
The backend derives `linkedAlertId` from the SuspiciousTransaction read model and validates the relationship before
returning alert context. The loaded SuspiciousTransaction document must match the path `suspiciousTransactionId`.
The client cannot pass `alertId`.
The SuspiciousTransaction linked-alert UI uses the backend relationship resolver.
AlertReadOnlyContextPage calls GET `/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`.
`/internal` in this API means non-public product API for the protected analyst console. It does not mean service-private
or unauthenticated. The endpoint remains a protected HTTP API with backend authorization, state-driven response
semantics, audit/metrics expectations, and compatibility obligations for the analyst console.
The endpoint is not public API, but it is also not backend-private when used by the analyst console.
The UI does not call GET `/api/v1/alerts/{alertId}` for SuspiciousTransaction linked-alert context.
The frontend sends `suspiciousTransactionId` only and does not send `alertId` or `linkedAlertId` to the resolver.
The frontend does not send alert, linked-alert, customer, account, transaction, correlation, or scoreDecision
identifiers to the resolver through query parameters, request body, or custom headers.
`linkedAlertId` may be displayed as reference-only SuspiciousTransaction detail context, but it does not drive the linked-alert context fetch.
Backend relationship validation is authoritative.
HTTP 200 does not imply available context; the UI evaluates response.state before rendering.
Only state `LINKED_ALERT_AVAILABLE` renders alert fields. Non-available states render no alert fields.
WorkspaceDetailRouter owns route/source readiness for linked-alert context.
WorkspaceDetailRouter may check that `sourceSuspiciousTransaction.suspiciousTransactionId` matches the selected route
`selectedSuspiciousTransactionId` before mounting linked-alert context. This prevents stale route/source races and is
UX route readiness, not linked-alert relationship validation.
A source identifier mismatch fails closed before any linked-alert resolver fetch.
A known source identifier mismatch is not treated as normal loading. It renders an explicit fail-closed
stale-source/source-mismatch state without raw identifiers.
AlertReadOnlyContextPage owns resolver state rendering.
The backend owns linked-alert relationship validation.
No frontend relationship validation is a source of truth.
The frontend does not compare `linkedAlertId`, alert transaction, customer, account, correlation, or score decision
fields to validate the linked-alert relationship.
AlertReadOnlyContextPage does not receive `sourceSuspiciousTransaction`, `alertId`, or `linkedAlertId`.

Alert detail is investigation context. It is not confirmed fraud, not an analyst decision, not a final outcome,
not a case lifecycle action, and not legal proof.

The bridge does not mutate SuspiciousTransaction, Alert, FraudCase, or AnalystDecision. It does not create or update
cases, submit analyst decisions, expose assistant summary, expose an evidence proof panel, or expose full evidence
snapshot expansion.

The frontend guard is not a security boundary. Backend authorization and relationship validation remain authoritative.
`SUSPICIOUS_TRANSACTION_READ` does not imply alert read access. Missing `linkedAlertId` shows no action.
Missing alert read authority shows no actionable alert link or an access-denied state.
The endpoint requires both `SUSPICIOUS_TRANSACTION_READ` and `ALERT_READ`.

Linked alert context is opened from SuspiciousTransaction detail view and route/state must retain the source
`suspiciousTransactionId`. An `alertId` alone in the SuspiciousTransaction workspace is invalid bridge context.
Frontend source-context binding is scope control, not security enforcement.
The backend must not accept `alertId` in the path, query string, request body, or custom headers for this context.
The client must not send `linkedAlertId`, customerId, accountId, transactionId, correlationId, or scoreDecisionId in
query parameters, request body, or custom headers for this context. Relationship mismatch fails closed without alert
fields. Relationship validation currently uses alertId, transactionId, customerId, and
correlationId where available.

## Backend DTO Boundary

The linked-alert UI relies on the backend resolver returning `AlertLinkedContextResponse`.
The UI must not consume full `AlertDetailsResponse` for SuspiciousTransaction linked-alert context.
The backend DTO must remain a minimal allowlisted DTO.
`LINKED_ALERT_AVAILABLE` may render only fields returned by the minimal linked-alert context DTO.
Non-available states render no alert fields.
If the backend DTO expands, UI review must verify no workflow, analyst decision, final outcome, assistant summary,
evidence snapshot, raw payload, or case lifecycle fields are displayed.

The linked-alert response is minimal read-only context: alertId, transactionId, customerId, accountId when already
present, alert score, riskLevel, alertStatus, reasonCodes, createdAt, updatedAt only when the alert read model exposes a
reliable updated timestamp, correlationId, and scoreDecisionId when already present.
updatedAt is nullable. Clients must not assume it is present. createdAt must not be treated as update time, and the
endpoint must not synthesize fake updatedAt values.
scoreDecisionId is sourced from SuspiciousTransaction.
scoreDecisionId is lineage metadata for the source suspicious signal.
scoreDecisionId is not used for alert-side compatibility unless the alert read model exposes an equivalent field.
It must not expose analyst decisions, idempotency keys, case lifecycle state, assistant summaries, legal-proof material,
full evidence snapshots, raw payloads, score details, or feature snapshots.

Sensitive read audit remains the existing audit policy. Metrics are separate diagnostic signals and do not replace audit.
FDP-72 forbids raw identifiers in metrics and ordinary logs. It does not change existing sensitive-read audit policy.
The source SuspiciousTransaction resourceId may remain the audited resource identifier.
Audit is a controlled security/audit channel and is not the same as metrics or ordinary logs.
Audit access, storage, and retention must be governed by the existing audit policy.
FDP-72 records bounded backend resolver outcome metrics for linked-alert context reads.
The metric name is `fraud.suspicious_transaction.linked_alert.read`.
Allowed metric labels are `endpoint=linked_alert_context` and the allowlisted `outcome` values: `available`,
`no_linked_alert`, `linked_alert_not_found`, `relationship_mismatch`, `temporarily_unavailable`, `validation_error`,
`suspicious_transaction_not_found`, and `error`.
`validation_error` means the client supplied an unsupported selector such as `alertId`; it is a bounded endpoint outcome, not raw validation detail.
`suspicious_transaction_not_found` means the source SuspiciousTransaction was not found; it is a bounded endpoint outcome, not a raw identifier.
FDP-72 replaces the previous linked-alert metric outcome label `unavailable` with `temporarily_unavailable` to align
with the resolver response state. Existing dashboards or ad-hoc queries using `outcome=unavailable` must migrate to
`outcome=temporarily_unavailable`.
The endpoint label `endpoint=linked_alert_context` is a constant label introduced with the bounded recorder contract.
FDP-72 does not dual-emit the legacy `unavailable` label and does not add a compatibility metric unless explicitly required by operations.
Metrics observe resolver state, not entities.
Metrics must never contain raw identifiers, raw paths, raw query strings, raw exception messages, request bodies,
response bodies, or idempotency keys. Metrics and ordinary logs must not log raw identifiers.
Metrics failure must not alter the linked-alert read response.
Custom recorder implementations must not log raw identifiers or exception messages, even when rethrowing.
Resolver outcome metrics are bounded and do not contain raw identifiers, but metrics dashboards and metric query access
should remain access-controlled.
Aggregated outcomes such as suspicious_transaction_not_found may still be operationally sensitive in small environments.
FDP-72 does not add frontend behavior, DTO fields, endpoint behavior, authorization behavior, workflow behavior,
dashboards, alerting thresholds, or tracing rollout.
Linked-alert context read uses the existing sensitive-read audit target policy. The source SuspiciousTransaction
resourceId may be used as the audited resource identifier. Audit metadata must not include raw alertId, raw
linkedAlertId, raw customerId, raw accountId, raw transactionId, raw correlationId, raw scoreDecisionId, raw query
string, raw request path, raw exception message, or response body.

Clients must evaluate state. HTTP 200 does not imply linked alert context is available.
HTTP 200 does not imply LINKED_ALERT_AVAILABLE.
TEMPORARILY_UNAVAILABLE is a degraded read state and must not be rendered as linked alert context.
UI/client must not render alert context fields for TEMPORARILY_UNAVAILABLE.
TEMPORARILY_UNAVAILABLE returns no alert fields and records the bounded `temporarily_unavailable` resolver outcome
when that state is returned. Unexpected resolver failures record the bounded `error` metric outcome. A rejected
client-supplied `alertId` query parameter returns 400 and records a bounded validation error metric.

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
