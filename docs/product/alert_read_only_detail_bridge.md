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
FDP-69 does not migrate the UI workflow. Until a dedicated UI migration uses this endpoint, the existing
`AlertReadOnlyContextPage` remains a getAlert-only read component guarded by frontend source-context binding and backend
`ALERT_READ` authorization.

## Semantics

Alert detail is investigation context.
Alert detail is not confirmed fraud.
Alert detail is not an analyst decision.
Alert detail is not a final outcome.
Alert detail is not a case lifecycle action.
Alert detail is not legal proof.

The bridge must not rename an alert into a fraud verdict, analyst disposition, case outcome, or final decision.

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

FDP-67 is not a general-purpose alert lookup inside the SuspiciousTransaction workspace.
The backend loads the source SuspiciousTransaction, derives `linkedAlertId`, loads the alert, and fails closed if the
relationship does not match. Frontend context binding is scope control, not security boundary.
Backend relationship validation and alert-read authorization remain authoritative.

## Dedicated Read-Only Component

FDP-68 fully removes SuspiciousTransaction read-only bridge mode from `AlertDetailsPage`.
`AlertDetailsPage` no longer accepts `readOnlyContext` for the SuspiciousTransaction bridge.
`AlertDetailsPage` remains the workflow-capable normal alert detail page.
`AlertReadOnlyContextPage` is the dedicated read-only alert context page and the only component for SuspiciousTransaction linked-alert read-only context.
`AlertReadOnlyContextPage` is the only component for SuspiciousTransaction linked-alert read-only context.
`AlertReadOnlyContextPage` depends only on a getAlert-only client.
This removes the boolean-mode smell and makes the read-only path safe by construction instead of conditionals inside the workflow page.
The internal linked-alert endpoint is the backend source for validated read-only alert context when the UI is migrated to
the FDP-69 contract.

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
- updatedAt when already present in the linked read model
- scoreDecisionId when already present in the linked SuspiciousTransaction context

The response must not expose analyst decisions, idempotency keys, case lifecycle state, assistant summaries, full
evidence snapshots, legal-proof material, raw payloads, score details, or feature snapshots.

Sensitive read audit and metrics use bounded outcome labels only: available, no linked alert, suspicious transaction not
found, linked alert not found, relationship mismatch, unavailable, or error. Logs, metrics, and audit metadata must not
record raw alert IDs, customer/account identifiers, correlation IDs, raw paths, raw query strings, exception messages,
or idempotency keys.

## UI States

Missing `linkedAlertId` shows `No linked alert` and does not render an action.
Missing alert read authority shows no actionable alert link or an access-denied state.
Backend 401 and 403 responses render an access-denied state.
Missing SuspiciousTransaction renders a safe not-found state.
Missing linked alert renders a bounded linked-alert-not-found state without alert fields.
Relationship mismatch renders a bounded mismatch state without alert fields.
Other backend failures render a safe unavailable state and must not display raw backend payloads.

## Non-Claims

This bridge is not fraud confirmation.
This bridge is not a case decision.
This bridge is not a final outcome.
This bridge is not an analyst disposition.
This bridge is not legal proof.
This bridge is not a complete investigation view.
