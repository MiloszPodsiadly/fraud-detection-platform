# Transaction Risk Intelligence UI

Status: FDP-116/FDP-118 diagnostics, FDP-119 analyst recommendation display, and FDP-121 bounded analyst feedback capture.

## Scope

FDP-116 adds read-only transaction diagnostic sections for a single scored transaction. The panel consumes the FDP-115
scored transaction detail API:

`GET /api/v1/transactions/scored/{transactionId}`

The UI displays bounded Engine Intelligence diagnostics and the FDP-119 `analystRecommendation` detail field already
stored and exposed by alert-service. FDP-121 adds an `Analyst Feedback` section that uses the alert-service feedback
API for explicit analyst review outcome capture.

## Boundaries

- Frontend-only display change in `analyst-console-ui`.
- The scored transaction list remains a lightweight list.
- The panel fetches detail only for the explicitly expanded transaction row.
- The panel does not use the older dedicated Engine Intelligence read endpoint as its main source.
- The panel uses FDP-121 feedback endpoints only for the `Analyst Feedback` section.
- The panel does not compute Analyst Recommendation values.
- Backend authorization remains authoritative; frontend capability and rendering choices are not security decisions.

## Analyst Feedback Capture

The panel calls:

`GET /api/v1/transactions/scored/{transactionId}/feedback`

and, only after explicit analyst form submit:

`POST /api/v1/transactions/scored/{transactionId}/feedback`

The UI allows only bounded labels and neutral decision names. It renders existing feedback as read-only and does not
offer a second submit when feedback already exists. Duplicate POST returns `409 CONFLICT`.

Decision and label must match the backend contract: `MARKED_FRAUD` with `CONFIRMED_FRAUD`, `MARKED_LEGITIMATE` with
`CONFIRMED_LEGITIMATE`, `MARKED_INCONCLUSIVE` with `INCONCLUSIVE`, and `REQUESTED_MORE_INFO` with `NEEDS_MORE_INFO`.
Reason codes come from the bounded FDP-121 allowlist only and must match the selected feedback label:
`CONFIRMED_FRAUD` uses fraud-confirming reason codes, `CONFIRMED_LEGITIMATE` uses legitimate-confirming reason
codes, `INCONCLUSIVE` uses inconclusive evidence reason codes, and `NEEDS_MORE_INFO` uses more-information reason
codes.

Feedback records analyst review outcome only. It does not authorize payment, approve, decline, block, change scoring,
update recommendations, create cases, trigger workflow, train models, promote models, or change thresholds.

Analyst feedback labels are future evaluation signals, not certified legal ground truth. Notes are bounded, normalized
before submit, stored for analyst context only, not returned raw in existing-feedback responses, not audited, and not
used for ML training or dataset export in FDP-121.

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

The Transaction Risk Intelligence UI does not add case actions, payment authorization, model promotion, threshold
recommendation, workflow behavior, scoring changes, recommendation mutation, dataset export, ML evaluation, or raw
payload display. FDP-121 adds bounded feedback submission only.

## FDP-118 UI Hardening

FDP-118 hardens the FDP-116 Transaction Risk Intelligence diagnostic UI. The change improves layout, visual grouping,
diagnostic boundary wording, status presentation, empty states, error states, accessibility, keyboard-oriented tests,
safe fixtures, scope guards, and documentation.

The diagnostic sections remain frontend-only and read-only. FDP-121 adds a separate bounded `Analyst Feedback` write
record; that feedback surface is not part of the diagnostic read model. The panel continues to consume only the
FDP-115 scored transaction detail API for transaction diagnostics:

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
detail response. FDP-121 adds bounded Analyst Feedback capture but still does not add apply/accept/reject behavior,
case creation, workflow mutation, payment authorization, model promotion, threshold changes, scoring changes, or
frontend recommendation logic.
