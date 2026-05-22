# Fraud Case Evidence Summary UI

Status: current product UI source of truth.

## Purpose

FDP-74 renders the FDP-73 fraud-case evidence summary as read-only investigation context inside the existing fraud case
detail view.

Endpoint:
`GET /api/v1/fraud-cases/{caseId}/evidence-summary`

Core rule:
Show context. Do not decide. Do not mutate. Do not prove.

The section helps analysts see bounded evidence metadata already projected by the backend. It does not load full alert
details, suspicious transaction detail, raw evidence, or any case lifecycle write surface.

## UI Placement

The section is mounted in the existing FraudCase detail page, separate from the case workflow rail. Existing FraudCase
detail workflow controls may remain elsewhere on the page. FDP-74 does not add, remove, or redefine those controls.

The section uses the existing fraud-case read capability model and requires the surrounding detail page to have a case id.
If no case id is available, the section must not call the evidence summary endpoint.

## API Client Boundary

The frontend API client exposes only:
`getFraudCaseEvidenceSummary(caseId, options)`

The method calls only the FDP-73 endpoint with an encoded `caseId`. It does not send a request body, query selectors,
raw selector headers, or alternate identifiers such as alert id, linked alert id, suspicious transaction id, transaction
id, customer id, account id, or evidence id.

The UI must not fall back to alert detail APIs or suspicious transaction APIs when this read fails.

## Rendered Fields

Allowed fields:

- aggregate evidence status labelled as `Evidence status`
- reason codes
- evidence by source
- evidence by status
- linked alert count
- evidence item count
- truncation reason
- highest severity evidence fields: title, description, reason code, evidence type, severity, source, status

Excluded fields:

- raw payloads
- raw attributes
- raw model details
- feature snapshots
- source event ids
- raw alert, customer, account, transaction, correlation, score decision, or evidence identifiers
- final outcome fields
- analyst decision fields
- raw backend error payloads

The component must not render a JSON inspector, raw evidence drilldown, alert detail drilldown, mutation controls, or
case workflow handlers.

## Safe States

The section renders bounded user-facing states:

- `Loading evidence summary…`
- `Legacy context. This case may not have structured evidence summary data.`
- `Evidence summary unavailable.`
- `Partial summary. Some linked evidence context is incomplete or unavailable.`
- `Truncated summary. Only the first bounded set of linked alert evidence was included.`

Backend errors, including authorization failures, render the same safe unavailable state and must not display raw backend
messages. Evidence summary failure must not break the rest of the fraud case detail view.

## Required Helper Text

The section displays:

`Evidence summary is read-only investigation context. It is not confirmed fraud, not an analyst decision, not a final outcome, and not legal proof.`

## Non-Claims

This UI section is not fraud confirmation, not a case decision, not a final outcome, not an analyst disposition, not
legal proof, and not a complete investigation view.

It is not a workflow surface, not a write path, not an evidence editor, not an export surface, and not a replacement for
full case review.

## Merge Gate

- Frontend only; no backend endpoint, DTO, authority, or service changes.
- Uses only `GET /api/v1/fraud-cases/{caseId}/evidence-summary`.
- Encodes `caseId` and does not send body, query selectors, raw selector headers, or alternate identifiers.
- Does not call alert detail or suspicious transaction APIs as fallback.
- Renders only bounded read-only investigation context.
- Does not render raw payloads, raw identifiers, JSON inspector, drilldowns, or mutation controls.
- Does not expose raw backend errors.
- Evidence summary failure does not break the rest of FraudCase detail.
