# Shadow Performance Dashboard UI

The Shadow Performance dashboard adds a read-only analyst-console UI over the v2 endpoint:

`GET /api/v2/governance/shadow-performance/summary/current`

The UI requires the existing `shadow-performance:read` authority as a frontend visibility and request gate. Backend authorization remains authoritative.

## Scope

- The dashboard consumes only the v2 current Shadow Performance Summary endpoint.
- The Shadow Performance workspace consumes only the v2 current summary endpoint.
- The Shadow Performance workspace does not fetch global platform counters.
- The Shadow Performance workspace does not render global transaction, alert, suspicious-transaction, or fraud-case counters as part of Shadow Performance diagnostics.
- FDP-107 does not compute metrics.
- FDP-107 does not recompute shadow performance.
- FDP-107 does not read raw artifacts, raw Platform Recommendation Evaluation Card data, raw FDP-103 reports, or FDP-102 JSONL datasets.
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

The dashboard renders only bounded v2 response fields: platform-recommendation subject identity, governance context, evaluation lineage context, evaluation population, metric availability objects, warnings, limitations, and the diagnostic-only banner.

The diagnostic banner states that shadow performance metrics are offline diagnostics only and are not promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.

## 404 Empty State

When the v2 endpoint returns 404 for `GET /api/v2/governance/shadow-performance/summary/current`, it means no current validated Shadow Performance Summary exists in that backend environment.

This 404 state is not a model quality result. The UI must not display fake, zero, sample, fallback, stale, or cached metrics on 404.

Metrics appear only after the v2 read API exposes a current validated summary. The 404 state remains read-only and diagnostic-only, and it does not approve production decisioning, recommend promotion, recommend thresholds, authorize payments, or influence analyst recommendations.

The 404 state remains context-only. It does not show unrelated platform counters as shadow metrics or as evidence of Shadow Performance health.
