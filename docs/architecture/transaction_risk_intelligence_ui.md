# Transaction Risk Intelligence UI

Status: FDP-116/FDP-118 read-only frontend diagnostics with FDP-119 analyst recommendation display.

## Scope

FDP-116 adds a read-only Analyst Console panel for a single scored transaction. The panel consumes the FDP-115
scored transaction detail API:

`GET /api/v1/transactions/scored/{transactionId}`

The UI displays bounded Engine Intelligence diagnostics and the FDP-119 `analystRecommendation` detail field already
stored and exposed by alert-service. It does not create a new backend API, OpenAPI contract, scoring path, workflow
route, or write surface.

## Boundaries

- Frontend-only display change in `analyst-console-ui`.
- The scored transaction list remains a lightweight list.
- The panel fetches detail only for the explicitly expanded transaction row.
- The panel does not use the older dedicated Engine Intelligence read endpoint as its main source.
- The panel does not use feedback endpoints.
- The panel does not compute Analyst Recommendation values.
- Backend authorization remains authoritative; frontend capability and rendering choices are not security decisions.

## Display Rules

The frontend display validator requires:

- `transactionId`
- `engineIntelligence`
- status in `AVAILABLE`, `ABSENT`, `UNAVAILABLE`, or `DEGRADED`
- bounded arrays: engines max 2, diagnostic signals max 5, warnings max 10, reason codes max 5 per engine
- `analystRecommendation`
- analyst recommendation status in `AVAILABLE`, `ABSENT`, `NOT_APPLICABLE`, `INSUFFICIENT_DATA`, `UNAVAILABLE`, or `DEGRADED`
- recommendation values only for `AVAILABLE` and `DEGRADED`
- no recommendation values for `ABSENT`, `NOT_APPLICABLE`, `INSUFFICIENT_DATA`, or `UNAVAILABLE`
- `recommendationVersion` as a non-empty string
- `generatedAt` as a parseable timestamp for `AVAILABLE` and `DEGRADED`, or `null` for non-generated states
- `source` as a required non-null public enum value
- analyst recommendation reason codes max 5 and warnings max 10
- all analyst recommendation non-decisioning flags set to true

The panel displays projected comparison fields as-is:

- `agreementStatus`
- `riskMismatchStatus`
- `scoreDeltaBucket`

It does not compute comparison, rank engines, infer a winning engine, infer a final outcome, infer a recommendation,
or display raw payloads.

## Analyst Recommendation Display

FDP-119 adds a read-only Analyst Recommendation section after Projected Comparison and before Engine Results. The
section displays status, recommendation value when present, recommendation version, generated timestamp when present, source,
confidence, reason codes, warnings, and the non-decisioning boundary. It uses safe copy for all statuses:

- `AVAILABLE`: a bounded advisory recommendation is available.
- `DEGRADED`: a recommendation is available with diagnostic limitations.
- `ABSENT`: no recommendation was produced; this is not `RECOMMEND_NO_ACTION`.
- `NOT_APPLICABLE`: the layer did not produce a recommendation for this transaction.
- `INSUFFICIENT_DATA`: bounded diagnostic evidence is not enough to produce a recommendation.
- `UNAVAILABLE`: the recommendation is unavailable.

`RECOMMEND_NO_ACTION` is displayed only when explicitly returned by the detail API. It is not approval, payment
authorization, fraud clearance, or a final bank decision.

## Non-Claims

The Transaction Risk Intelligence UI does not add feedback submission, case actions, payment authorization, model
promotion, threshold recommendation, workflow behavior, scoring changes, backend mutations, OpenAPI changes, or raw
payload display. FDP-119 adds read-only rendering of the existing detail API `analystRecommendation` field only.

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

FDP-119 changes that non-claim only by adding read-only Analyst Recommendation rendering from the scored transaction
detail response. It still does not add buttons, forms, apply/accept/reject behavior, feedback, case creation, workflow
mutation, payment authorization, model promotion, threshold changes, scoring changes, or frontend recommendation logic.
