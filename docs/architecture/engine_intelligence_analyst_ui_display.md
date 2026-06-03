# Engine Intelligence Analyst UI Display

## Purpose

FDP-97 displays bounded transaction-level engine intelligence from the FDP-96 API.

## Scope

FDP-97 is read-only diagnostic UI. The panel is shown inside the existing fraud case transaction detail view where a transaction ID is already known.

## Non-goals

FDP-97 does not add feedback workflow.
FDP-97 does not add analyst actions.
FDP-97 does not add approve/decline/block.
FDP-97 does not add final decisioning.
FDP-97 does not change alert severity.
FDP-97 does not change fraud case status.
FDP-97 does not add case-level aggregation.

## API Dependency

UI consumes only `GET /api/v1/transactions/scored/{transactionId}/engine-intelligence`.
The UI does not call Mongo, scoring, ML, rules, orchestrator, Kafka, feedback, alert mutation, fraud case mutation, severity mutation, or status mutation APIs for this display.

## UI Placement

Engine intelligence is rendered under an expanded grouped transaction in `FraudCaseDetailsPage`.
The panel receives only `transactionId` and a narrow `getEngineIntelligence` function.
The panel is not a dashboard, list view, search view, or bulk view.

## State Matrix

Loading is non-blocking and does not block the fraud case page.
`available=true` renders bounded comparison, engine results, diagnostic signals, and warnings.
`available=false` with `NOT_PROJECTED` renders a neutral unavailable-for-transaction state.
`403` renders access denied without raw backend details.
`404` renders transaction not found without raw backend details.
`503`, network failure, server failure, and unexpected response shape render the same temporary unavailable state.

## Operational Wording

TIMEOUT/UNAVAILABLE/DEGRADED are operational statuses, not LOW risk.
The UI renders TIMEOUT as engine timed out, UNAVAILABLE as engine unavailable, and DEGRADED as engine response degraded.
Operational status wording must not imply safe, no fraud, less severe, not suspicious, or low risk because an engine is unavailable.

## No Decisioning Wording

UI wording must avoid final/recommended/winning/safe/approve/decline/block.
Diagnostic comparison is displayed as diagnostic only and must not become a recommendation, action, platform verdict, or decision source.

## No Raw/Internal Rendering

UI must not render raw error bodies or internal fields.
The panel renders only allowlisted FDP-96 fields and does not dump whole JSON.
It must not render rawEvidence, rawContribution, featureSnapshot, featureVector, rawPayload, endpoint, token, secret, stacktrace, exceptionMessage, Mongo metadata, internal projection class names, FraudEngineAggregationResult, NormalizedFraudEngineResult, or ScoringContext.

## No Feedback Workflow

FDP-97 does not add feedback workflow, feedback submit, analyst action submit, or mutation buttons.

## No Case-Level Aggregation

FDP-97 remains transaction-level and does not add case-level aggregation, global dashboard, list/search engine intelligence page, or bulk view.

## Accessibility

The panel has an accessible heading.
Loading, empty, access denied, not found, unavailable, and available states use textual status labels.
No information is conveyed by color only.
Keyboard navigation remains the same as the existing expanded transaction detail flow.

## Future FDP-98 Feedback Workflow

FDP-98 is the future feedback workflow branch.
