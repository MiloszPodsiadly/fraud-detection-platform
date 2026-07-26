# Shadow Performance Read API

Status: Shadow Performance Summary v2 authorized read API.

## Scope

The API provides one authorized, read-only API boundary for the current validated Shadow Performance Summary v2:

```text
GET /api/v2/governance/shadow-performance/summary/current
```

The endpoint requires the explicit `shadow-performance:read` authority. Generic transaction read, fraud-case read,
analyst read, and advisory read authorities do not grant access to this summary unless a future security review maps
them explicitly.

## Source Of Truth

The API exposes only validated Shadow Performance Summary v2 fields through bounded response DTOs. It does not
recompute metrics, rebuild the summary, generate Platform Recommendation Evaluation Cards, read dataset JSONL exports, read raw offline
evaluation reports, read raw Platform Recommendation Evaluation Card payloads, inspect raw dataset rows, read production scoring DBs, call scoring
services, call Kafka, read model registry state, or read model artifact stores.

The endpoint does not recompute metrics. The endpoint does not read raw dataset exports. The endpoint does not expose raw Platform Recommendation Evaluation Cards.

The source chain remains:

```text
FDP-123 bounded feedback dataset
-> FDP-124 evaluation artifact set
-> Platform Recommendation Evaluation Card v1 artifact set
-> Shadow Performance Summary v2
-> v2 read API DTO
```

The API is only the read boundary over Shadow Performance Summary v2. Default runtime does not expose static fixture data: if no
configured/current summary source exists, the production-safe provider returns empty and the endpoint fails closed with
`404`. Static fixture summaries are only test/demo/local fixtures when explicitly instantiated by tests or local tooling.

## Response Boundary

The response includes report identity, platform recommendation evaluation subject, metric basis, diagnostic governance, evaluation context, evaluation
population, metric availability objects, warnings, limitations, and the diagnostic-only banner. It does not expose
raw Platform Recommendation Evaluation Cards, raw evaluation reports, raw dataset exports, per-record examples, pseudonymous evaluation references,
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

The API uses the existing sensitive-read audit boundary with endpoint category `SHADOW_PERFORMANCE_SUMMARY` and resource
type `SHADOW_PERFORMANCE_SUMMARY`. Audit metadata is bounded and low-cardinality. It does not store the raw response
body, raw metrics blob, raw Platform Recommendation Evaluation Card, raw evaluation report, raw dataset data, per-record identifiers, tokens, secrets,
stack traces, or raw exception messages.

Production/operator-facing exposure must keep this endpoint aligned with the platform sensitive-read audit policy.
Successful reads are audited by the existing sensitive-read response advice; failed provider or invalid-summary reads are
classified by the sensitive-read failure interceptor, with 5xx outcomes treated as `FAILED`.

## Non-Goals

The API does not add UI, dashboards, charts, filters, search, list-all summaries, historical trends, model comparison
tables, promotion workflows, promotion readiness, threshold recommendations, threshold switching, champion/challenger
logic, retraining, model registry mutation, model artifact mutation, production scoring changes, Kafka changes,
TransactionScoredEvent changes, alert-service projection changes, payment authorization, fraud-case status mutation,
alert severity mutation, or analyst recommendation influence.
