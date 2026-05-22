# Alert Read-Only Detail Bridge

Status: current product documentation for the SuspiciousTransaction linked-alert read-only bridge.

## Purpose

The bridge provides read-only navigation from SuspiciousTransaction detail to linked alert context.
The bridge gives analysts alert investigation context without opening an alert workflow surface.
`AlertReadOnlyContextPage` remains the dedicated UI component for this context.

The backend relationship validation is authoritative. The backend exposes a dedicated internal read endpoint with the
source SuspiciousTransaction identifier:

- GET `/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`

The backend derives `linkedAlertId` from the SuspiciousTransaction read model and validates the relationship before
returning alert context. Clients cannot pass `alertId` in the path, query string, or request body.
Clients cannot pass `alertId`, `linkedAlertId`, alert, customer, account, transaction, correlation, or scoreDecision
identifiers through query parameters, request body, or custom headers for this context.
The loaded SuspiciousTransaction document must also match the path `suspiciousTransactionId`; a source identifier
mismatch fails closed before any alert lookup.
The SuspiciousTransaction linked-alert UI uses the backend linked-alert resolver.
`AlertReadOnlyContextPage` calls GET `/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`.
`/internal` in this API means non-public product API for the protected analyst console. It does not mean service-private
or unauthenticated. The endpoint remains a protected HTTP API with backend authorization, state-driven response
semantics, audit/metrics expectations, and compatibility obligations for the analyst console.
The endpoint is not public API, but it is also not backend-private when used by the analyst console.
The frontend sends `suspiciousTransactionId` only.
The frontend does not send `alertId` or `linkedAlertId` to the resolver through URL, query, body, or custom headers.
The frontend does not send customerId, accountId, transactionId, correlationId, or scoreDecisionId to the resolver
through URL, query, body, or custom headers.
`linkedAlertId` may be displayed as a reference-only field in SuspiciousTransaction detail, but it does not drive the
linked-alert context fetch.
The SuspiciousTransaction linked-alert UI does not call GET `/api/v1/alerts/{alertId}` for linked-alert context.
The SuspiciousTransaction linked-alert UI does not fallback to the general alert lookup for linked-alert context.

## Semantics

Alert detail is investigation context.
Alert detail is not confirmed fraud.
Alert detail is not an analyst decision.
Alert detail is not a final outcome.
Alert detail is not a case lifecycle action.
Alert detail is not legal proof.

The bridge must not rename an alert into a fraud verdict, analyst disposition, case outcome, or final decision.

## Route Readiness vs Relationship Validation

`WorkspaceDetailRouter` owns route/source readiness for this bridge.
The router may check that the loaded source SuspiciousTransaction `suspiciousTransactionId` matches the selected route
`suspiciousTransactionId` before mounting linked-alert context. This prevents stale route/source races.
The source identifier mismatch fails closed before any linked-alert resolver fetch.
Known source identifier mismatch is not treated as normal loading. It renders an explicit fail-closed
stale-source/source-mismatch state.
The stale-source/source-mismatch state is safe UI copy only and does not show raw identifiers.
This allowed frontend check is UX route readiness, not linked-alert relationship validation.

Allowed frontend route readiness check:

- `sourceSuspiciousTransaction.suspiciousTransactionId === selectedSuspiciousTransactionId`

Forbidden frontend relationship checks:

- `sourceSuspiciousTransaction.linkedAlertId === alertId`
- `alert.transactionId === sourceSuspiciousTransaction.transactionId`
- `alert.customerId === sourceSuspiciousTransaction.customerId`
- `alert.accountId === sourceSuspiciousTransaction.accountId`
- `alert.correlationId === sourceSuspiciousTransaction.correlationId`
- `alert.scoreDecisionId === sourceSuspiciousTransaction.scoreDecisionId`

The backend derives `linkedAlertId`, loads the alert, validates the linked-alert relationship, and returns
`response.state`.
The frontend does not validate the linked-alert relationship.

## Authorization Boundary

SuspiciousTransaction detail still requires `SUSPICIOUS_TRANSACTION_READ`.
Linked alert detail requires `ALERT_READ`.
The internal linked-alert context endpoint requires both `SUSPICIOUS_TRANSACTION_READ` and `ALERT_READ`.
`SUSPICIOUS_TRANSACTION_READ` does not imply `ALERT_READ`.
In plain terms, suspicious transaction read access does not imply alert read access.

The frontend guard is a UX and request gate only.
The frontend guard is not a security boundary.
Backend authorization remains authoritative for linked alert context access.

If a user has `SUSPICIOUS_TRANSACTION_READ` but not `ALERT_READ`, the SuspiciousTransaction detail may show that alert detail requires alert read access, but it must not render an actionable alert detail link.

## Source Context Binding

The linked alert context is opened from SuspiciousTransaction detail view.
The route/state may retain `suspiciousTransactionId` and linked-alert context for UX continuity.
An `alertId` alone in the suspicious workspace is invalid bridge context.

This bridge is not a general-purpose alert lookup inside the SuspiciousTransaction workspace.
The backend loads the source SuspiciousTransaction, derives `linkedAlertId`, loads the alert, and fails closed if the
relationship does not match. Router source readiness is UX route readiness, not frontend relationship validation.
No frontend relationship validation is a source of truth.
Backend relationship validation and alert-read authorization remain authoritative.
Relationship validation currently uses alertId, transactionId, customerId, and correlationId where available.

## Dedicated Read-Only Component

`AlertDetailsPage` remains the workflow-capable normal alert detail page.
`AlertReadOnlyContextPage` is the dedicated read-only alert context page.
`AlertReadOnlyContextPage` is the only component for SuspiciousTransaction linked-alert read-only context.
`WorkspaceDetailRouter` owns route/source readiness for this bridge.
`AlertReadOnlyContextPage` owns resolver state rendering.
The backend owns linked-alert relationship validation.
`AlertReadOnlyContextPage` depends only on a linked-alert resolver client.
`AlertReadOnlyContextPage` does not receive the full API client.
`AlertReadOnlyContextPage` does not receive `sourceSuspiciousTransaction`, `alertId`, or `linkedAlertId`.
This removes the boolean-mode smell and makes the read-only path safe by construction instead of conditionals inside the workflow page.
The internal linked-alert endpoint is the backend source for validated read-only alert context.
`AlertReadOnlyContextPage` calls GET `/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`.
Frontend state is not authoritative. Backend relationship validation is authoritative.
HTTP 200 does not imply available context; the UI must evaluate response.state.
Only response.state `LINKED_ALERT_AVAILABLE` may render alert fields.
Non-available states render no alert fields.

## Backend DTO Boundary

The linked-alert UI relies on the backend resolver returning `AlertLinkedContextResponse`.
The UI must not consume full `AlertDetailsResponse` for SuspiciousTransaction linked-alert context.
The backend DTO must remain a minimal allowlisted DTO.
`LINKED_ALERT_AVAILABLE` may render only fields returned by the minimal linked-alert context DTO.
Non-available states render no alert fields.
If the backend DTO expands, UI review must verify no workflow, analyst decision, final outcome, assistant summary,
evidence snapshot, raw payload, or case lifecycle fields are displayed.

This component boundary is scope control, not a frontend security boundary.
Backend `ALERT_READ` authorization remains authoritative.

## Read-Only Boundary

The bridge does not mutate SuspiciousTransaction, Alert, FraudCase, or AnalystDecision.
The bridge does not create or update cases.
The bridge does not submit analyst decisions.
The bridge does not assign, claim, close, reopen, dismiss, escalate, or confirm alerts.
The bridge does not link cases.
The bridge does not add workflow, decision, case lifecycle, assistant summary, or evidence proof panels.
The bridge does not expose assistant summary.
The bridge does not expose an evidence proof panel.
The bridge does not expose full evidence snapshots or raw payload expansion.
The bridge does not log raw identifiers, emit raw identifier telemetry, or store raw identifiers in browser storage.

Allowed read-only fields are limited to minimal alert read-model context:

- alertId
- transactionId
- customerId
- accountId when already present in the linked read model
- correlationId
- riskScore or fraudScore as read-model score
- riskLevel
- alert status as operational read-model status
- reasonCodes as metadata
- createdAt
- updatedAt only when the alert read model exposes a reliable updated timestamp
- scoreDecisionId when already present in the linked SuspiciousTransaction context

updatedAt is nullable. Clients must not assume it is present. createdAt must not be treated as update time, and the
endpoint must not synthesize fake updatedAt values.

scoreDecisionId is sourced from SuspiciousTransaction lineage.
scoreDecisionId is lineage metadata for the source suspicious signal.
scoreDecisionId is not used for alert-side compatibility unless the alert read model exposes an equivalent field.

The response must not expose analyst decisions, idempotency keys, case lifecycle state, assistant summaries, full
evidence snapshots, legal-proof material, raw payloads, score details, or feature snapshots.

Sensitive read audit remains the existing audit policy. Metrics are separate diagnostic signals and do not replace audit.
FDP-72 records bounded backend resolver outcome metrics for linked-alert context reads.
The metric name is `fraud.suspicious_transaction.linked_alert.read`.
The linked-alert resolver metrics use bounded outcome labels only.
Allowed metric labels are:

- `endpoint=linked_alert_context`
- `outcome=available`
- `outcome=no_linked_alert`
- `outcome=linked_alert_not_found`
- `outcome=relationship_mismatch`
- `outcome=temporarily_unavailable`
- `outcome=validation_error`
- `outcome=suspicious_transaction_not_found`
- `outcome=error`

Metrics observe resolver state, not entities.
Metrics must never contain raw identifiers.
Metrics and ordinary logs must not log raw identifiers.
Metrics must never contain request path, query string, request body, response body, or raw exception message.
Metrics failure must not alter the linked-alert read response.
401 and 403 observability remains owned by the security layer. FDP-72 does not add an `authState` metric label.
FDP-72 does not add dashboards, alerting thresholds, tracing rollout, frontend behavior, DTO fields, endpoint behavior,
authorization behavior, or workflow behavior.

Audit and identifier policy:
Linked-alert context read uses the existing sensitive-read audit target policy.
The source SuspiciousTransaction resourceId may be used as the audited resource identifier.
Metrics and ordinary logs must not contain raw identifiers.
Audit entries must not record raw alertId, must not record raw linkedAlertId, must not record raw customerId, must not
record raw accountId, must not record raw transactionId, must not record raw correlationId, must not record raw
scoreDecisionId, must not record raw query strings, must not record raw request paths, must not record raw exception
message, and must not record response payloads. Query param `alertId` rejection must not audit the `alertId` value.
Audit entries must not record raw exception message.

Clients must evaluate state. HTTP 200 does not imply linked alert context is available.
HTTP 200 does not imply LINKED_ALERT_AVAILABLE.
TEMPORARILY_UNAVAILABLE is a degraded read state and must not be rendered as linked alert context.
UI/client must not render alert context fields for TEMPORARILY_UNAVAILABLE.
TEMPORARILY_UNAVAILABLE returns no alert fields, records the bounded `temporarily_unavailable` resolver outcome when
that state is returned, and records a failed sensitive-read audit attempt when the resolver caught an unexpected
backend failure.
Unexpected resolver failures record the bounded `error` metric outcome and must not display raw backend payloads.

## UI States

Missing `linkedAlertId` shows `No linked alert` and does not render an action.
Missing alert read authority shows no actionable alert link or an access-denied state.
Backend 401 and 403 responses render an access-denied state.
Missing SuspiciousTransaction renders a safe not-found state.
Missing linked alert renders a bounded linked-alert-not-found state without alert fields.
Relationship mismatch renders a bounded mismatch state without alert fields.
Rejected client-supplied `alertId` query parameters return 400 before service lookup.
Other backend failures render a safe unavailable state and must not display raw backend payloads.
Unknown linked-alert resolver state fails closed and renders no alert fields.

## Non-Claims

This bridge is not fraud confirmation.
This bridge is not a case decision.
This bridge is not a final outcome.
This bridge is not an analyst disposition.
This bridge is not legal proof.
This bridge is not a complete investigation view.
