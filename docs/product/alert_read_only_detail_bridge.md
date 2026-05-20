# Alert Read-Only Detail Bridge

Status: current product documentation for FDP-67.

## Purpose

FDP-67 adds read-only navigation from SuspiciousTransaction detail to linked alert detail.
The bridge gives analysts alert investigation context without opening an alert workflow surface.

The bridge uses the existing protected alert read API:

- GET `/api/v1/alerts/{alertId}`

FDP-67 does not introduce a second alert read API and does not add a new backend endpoint.

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
`SUSPICIOUS_TRANSACTION_READ` does not imply `ALERT_READ`.
In plain terms, suspicious transaction read access does not imply alert read access.

The frontend guard is a UX and request gate only.
The frontend guard is not a security boundary.
Backend authorization remains authoritative for direct and linked alert detail access.

If a user has `SUSPICIOUS_TRANSACTION_READ` but not `ALERT_READ`, the SuspiciousTransaction detail may show that alert detail requires alert read access, but it must not render an actionable alert detail link.

## Source Context Binding

The linked alert context is opened from SuspiciousTransaction detail view.
The route/state must retain `suspiciousTransactionId` and `linkedAlertId`.
An `alertId` alone in the suspicious workspace is invalid bridge context.
A `suspiciousTransactionId` plus `alertId` route without loaded source SuspiciousTransaction context is pending verification, not valid bridge context.

FDP-67 is not a general-purpose alert lookup inside the SuspiciousTransaction workspace.
The bridge requires source SuspiciousTransaction context before the frontend creates the read-only alert detail client.
The UI must not fetch alert detail until the source `linkedAlertId` matches the selected `alertId`.
If the source linked alert does not match the selected alert, the bridge fails closed.
Frontend context binding is scope control, not security boundary.
Backend alert-read authorization remains authoritative.

## Read-Only Boundary

The bridge does not mutate SuspiciousTransaction, Alert, FraudCase, or AnalystDecision.
The bridge does not create or update cases.
The bridge does not submit analyst decisions.
The bridge does not assign, claim, close, reopen, dismiss, escalate, or confirm alerts.
The bridge does not link cases.
The bridge does not expose assistant summary.
The bridge does not expose an evidence proof panel.
The bridge does not expose full evidence snapshots or raw payload expansion.

Allowed read-only fields are limited to alert read-model context already returned by GET `/api/v1/alerts/{alertId}`:

- alertId
- transactionId
- customerId
- correlationId
- riskScore or fraudScore as read-model score
- riskLevel
- alert status as operational read-model status
- reasonCodes as metadata
- createdAt
- alertTimestamp
- existing alert metadata returned by the protected read contract

## UI States

Missing `linkedAlertId` shows `No linked alert` and does not render an action.
Missing alert read authority shows no actionable alert link or an access-denied state.
Backend 401 and 403 responses render an access-denied state.
Backend 404 renders a safe not-found state.
Other backend failures render a safe unavailable state and must not display raw backend payloads.

## Non-Claims

This bridge is not fraud confirmation.
This bridge is not a case decision.
This bridge is not a final outcome.
This bridge is not an analyst disposition.
This bridge is not legal proof.
This bridge is not a complete investigation view.
