# Transaction Risk Intelligence UI

Status: FDP-116 implemented frontend read surface.

## Scope

FDP-116 adds a read-only Analyst Console panel for a single scored transaction. The panel consumes the FDP-115
scored transaction detail API:

`GET /api/v1/transactions/scored/{transactionId}`

The UI displays bounded Engine Intelligence diagnostics already projected by alert-service. It does not create a
new backend API, OpenAPI contract, scoring path, workflow route, or write surface.

## Boundaries

- Frontend-only display change in `analyst-console-ui`.
- The scored transaction list remains a lightweight list.
- The panel fetches detail only for the explicitly expanded transaction row.
- The panel does not use the older dedicated Engine Intelligence read endpoint as its main source.
- The panel does not use feedback endpoints.
- Backend authorization remains authoritative; frontend capability and rendering choices are not security decisions.

## Display Rules

The frontend display validator requires:

- `transactionId`
- `engineIntelligence`
- status in `AVAILABLE`, `ABSENT`, `UNAVAILABLE`, or `DEGRADED`
- bounded arrays: engines max 2, diagnostic signals max 5, warnings max 10, reason codes max 5 per engine

The panel displays projected comparison fields as-is:

- `agreementStatus`
- `riskMismatchStatus`
- `scoreDeltaBucket`

It does not compute comparison, rank engines, infer a winning engine, infer a final outcome, or display raw payloads.

## Non-Claims

FDP-116 does not add recommendation, feedback submission, case actions, payment authorization, model promotion,
threshold recommendation, workflow behavior, scoring changes, backend changes, OpenAPI changes, or raw payload
display.

## FDP-118 UI Hardening

FDP-118 hardens the FDP-116 read-only Transaction Risk Intelligence UI. The change improves layout, visual grouping,
diagnostic boundary wording, status presentation, empty states, error states, accessibility, keyboard-oriented tests,
safe fixtures, scope guards, and documentation.

The panel remains frontend-only and read-only. It continues to consume only the FDP-115 scored transaction detail API:

`GET /api/v1/transactions/scored/{transactionId}`

FDP-118 groups the panel into:

- Transaction Summary
- Engine Intelligence Status
- Projected Comparison
- Engine Results
- Diagnostic Signals
- Warnings and Limitations
- Diagnostic Boundary

The expanded transaction row keeps `aria-expanded` and adds `aria-controls` pointing at the stable panel id:

`transaction-risk-intelligence-{safeTransactionId}`

The id is sanitized for HTML usage only. The original transaction id remains the value passed to the detail API client.

FDP-118 does not add feedback submission, recommendation, case actions, approve/decline/block behavior, payment
authorization, model promotion, threshold recommendation, workflow, Kafka behavior, scheduler/cron/daemon behavior,
scoring changes, backend changes, OpenAPI changes, dependency changes, raw payload display, a global risk intelligence
dashboard, or bulk transaction intelligence export.
