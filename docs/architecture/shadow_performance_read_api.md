# Shadow Performance Read API

Status: FDP-106 authorized read API foundation.

## Scope

FDP-106 adds one authorized, read-only API boundary for the current validated FDP-105 Shadow Performance Summary:

```text
GET /api/v1/governance/shadow-performance/summary/current
```

The endpoint requires the explicit `shadow-performance:read` authority. Generic transaction read, fraud-case read,
analyst read, and advisory read authorities do not grant access to this summary unless a future security review maps
them explicitly.

## Source Of Truth

The API exposes only validated FDP-105 Shadow Performance Summary fields through bounded response DTOs. It does not
recompute metrics, rebuild the summary, generate FDP-104 Model Cards, read FDP-102 JSONL exports, read FDP-103 raw
evaluation reports, read raw FDP-104 Model Cards, inspect raw dataset rows, read production scoring DBs, call scoring
services, call Kafka, read model registry state, or read model artifact stores.

The endpoint does not recompute metrics. The endpoint does not read FDP-102 JSONL exports. The endpoint does not expose raw Model Cards.

The source chain remains:

```text
FDP-102 dataset export
-> FDP-103 offline evaluation report
-> FDP-104 Model Card v1
-> FDP-105 Shadow Performance Summary v1
-> FDP-106 read API DTO
```

FDP-106 is only the read API boundary over FDP-105. Default runtime does not expose static fixture data: if no
configured/current summary source exists, the production-safe provider returns empty and the endpoint fails closed with
`404`. Static fixture summaries are only test/demo/local fixtures when explicitly instantiated by tests or local tooling.

## Response Boundary

The response includes summary identity, model identity, diagnostic governance, evaluation context, evaluation
population, metrics, disagreement summary, warnings, limitations, and the diagnostic-only banner. It does not expose
raw Model Cards, raw FDP-103 reports, raw FDP-102 JSONL, per-record examples, pseudonymous evaluation references,
transaction references, raw payloads, raw feature vectors, tokens, secrets, stack traces, exception messages, payment
authorization fields, promotion fields, threshold recommendation fields, decisioning fields, or analyst recommendation
fields.

The response is diagnostic-only. It is not a dashboard, not model promotion approval, not threshold recommendation,
not production decisioning approval, not payment authorization, not automatic approve/decline/block logic, and not
analyst recommendation logic. It is not analyst recommendation logic.

## Error Semantics

- `200`: authorized actor and valid current summary exists.
- `401`: unauthenticated request according to platform security behavior.
- `403`: authenticated actor lacks `shadow-performance:read`.
- `404`: no current summary exists.
- `503`: summary provider is unavailable, sensitive-read audit is unavailable, or a current summary exists but fails validation.

Invalid current summaries are server-side summary availability failures. The API never returns an empty fake summary,
fabricated zero metrics, static fixture metrics by default, partial invalid summary, raw validation detail, raw exception
message, stack trace, file path, or raw artifact content.

## Audit

FDP-106 uses the existing sensitive-read audit boundary with endpoint category `SHADOW_PERFORMANCE_SUMMARY` and resource
type `SHADOW_PERFORMANCE_SUMMARY`. Audit metadata is bounded and low-cardinality. It does not store the raw response
body, raw metrics blob, raw Model Card, raw FDP-103 report, raw FDP-102 data, per-record identifiers, tokens, secrets,
stack traces, or raw exception messages.

Production/operator-facing exposure must keep this endpoint aligned with the platform sensitive-read audit policy.
Successful reads are audited by the existing sensitive-read response advice; failed provider or invalid-summary reads are
classified by the sensitive-read failure interceptor, with 5xx outcomes treated as `FAILED`.

## Non-Goals

FDP-106 does not add UI, dashboards, charts, filters, search, list-all summaries, historical trends, model comparison
tables, promotion workflows, promotion readiness, threshold recommendations, threshold switching, champion/challenger
logic, retraining, model registry mutation, model artifact mutation, production scoring changes, Kafka changes,
TransactionScoredEvent changes, alert-service projection changes, payment authorization, fraud-case status mutation,
alert severity mutation, or analyst recommendation influence.
