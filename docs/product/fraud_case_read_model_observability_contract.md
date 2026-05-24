# Fraud Case Read Model Observability Contract

Status: current product-domain observability contract for fraud-case evidence summary and evidence timeline reads.

## Purpose

FDP-79 adds bounded operational metrics for the read-only fraud-case evidence summary and evidence timeline endpoints.
The metrics help operators distinguish available, degraded, empty, missing, and failed reads without exposing case,
alert, customer, account, transaction, correlation, request, principal, or exception details.

## Scope

In scope:

- Evidence summary read model: `GET /api/v1/fraud-cases/{caseId}/evidence-summary`
- Evidence timeline read model: `GET /api/v1/fraud-cases/{caseId}/evidence-timeline`
- Low-cardinality endpoint and outcome labels
- Metric recording failure isolation
- Existing sensitive-read audit behavior unchanged

Out of scope:

- Frontend behavior
- New API fields, DTO shape changes, endpoints, authorities, request selectors, or query parameters
- Dashboard implementation
- Trace spans
- Audit redesign
- Mutation, case lifecycle, analyst decision, Kafka, outbox, or finality behavior

## Metric

Metric name:

`fraud.fraud_case.read_model.read`

Allowed labels:

| Label | Values |
| --- | --- |
| `endpoint` | `evidence_summary`, `evidence_timeline` |
| `outcome` | `available`, `partial`, `legacy`, `truncated`, `empty`, `not_found`, `error` |

No other labels are part of the FDP-79 contract.

## Outcome Semantics

Successful summary and timeline reads use the same precedence:

1. `truncated`
2. `legacy`
3. `partial`
4. `empty`
5. `available`

Missing fraud cases record `not_found`.
Unexpected runtime failures record `error`.

The `empty` outcome means the read model was available but contained no evidence items or no timeline events after
the bounded read logic completed.

## Safety Rules

Metrics must not use raw identifiers, request data, response payloads, exception text, principal data, result counts,
reason codes, evidence statuses, case statuses, or URL data as labels.

Forbidden label sources include:

- fraud case ids
- alert ids
- customer ids
- account ids
- transaction ids
- correlation ids
- request path, URI, query string, or query parameters
- authenticated principal or authority names
- exception class names or messages
- response result counts
- reason codes and evidence status values

Metric recording failure is isolated. A metrics backend error must not change endpoint status, response body,
not-found behavior, or sensitive-read audit behavior.

## Audit Relationship

FDP-79 does not replace sensitive-read audit.
Successful reads, missing-case attempts, unexpected failures, and audit failure behavior remain owned by the existing
fraud-case evidence summary and evidence timeline endpoint audit paths.

The metric is operational telemetry only. It is not proof of data access, not an audit event, not a legal record, not
a legal proof. It is not fraud confirmation, not a case decision, not a final outcome, and not an analyst disposition.
