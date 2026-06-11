# Shadow Performance Dashboard UI

FDP-107 adds a read-only analyst-console UI over the FDP-106 endpoint:

`GET /api/v1/governance/shadow-performance/summary/current`

The UI requires the existing `shadow-performance:read` authority as a frontend visibility and request gate. Backend authorization remains authoritative.

## Scope

- FDP-107 consumes only the FDP-106 current Shadow Performance Summary endpoint.
- FDP-107 does not compute metrics.
- FDP-107 does not recompute shadow performance.
- FDP-107 does not read raw artifacts, raw Model Card data, raw FDP-103 reports, or FDP-102 JSONL datasets.
- FDP-107 does not recommend promotion.
- FDP-107 does not recommend thresholds.
- FDP-107 does not approve production decisioning.
- FDP-107 does not affect production scoring.
- FDP-107 is not payment authorization.
- FDP-107 is not automatic decisioning.
- FDP-107 does not influence analyst recommendations or analyst actions.
- FDP-107 does not mutate alert state or fraud-case state.
- FDP-107 does not add filters, search, history, export, list-all summaries, pagination, model selection, model comparison, or trend charts.

## UI Behavior

The dashboard renders only bounded FDP-106 response fields: model identity, governance context, evaluation context, evaluation population, metrics, rule-vs-ML diagnostic disagreement, warnings, limitations, and the diagnostic-only banner.

The diagnostic banner states that shadow performance metrics are offline diagnostics only and are not promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.

## 404 Empty State

When FDP-106 returns 404 for `GET /api/v1/governance/shadow-performance/summary/current`, it means no current validated Shadow Performance Summary exists in that backend environment.

This 404 state is not a model quality result. The UI must not display fake, zero, sample, fallback, stale, or cached metrics on 404.

Metrics appear only after FDP-106 exposes a current validated summary. The 404 state remains read-only and diagnostic-only, and it does not approve production decisioning, recommend promotion, recommend thresholds, authorize payments, or influence analyst recommendations.
