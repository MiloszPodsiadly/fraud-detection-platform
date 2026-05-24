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

FDP-79 uses one shared read-model metric name with a bounded endpoint label instead of separate metric names per
endpoint. This keeps the contract consistent across FraudCase read models while preserving low cardinality.

Allowed labels:

| Label | Values |
| --- | --- |
| `endpoint` | `evidence_summary`, `evidence_timeline` |
| `outcome` | `available`, `partial`, `legacy`, `truncated`, `empty`, `not_found`, `error` |

No other labels are part of the FDP-79 contract.

The active FDP-79 metric contract is the shared metric above. `fraud.fraud_case.evidence_summary.read` and
`fraud.fraud_case.evidence_timeline.read` are not active metric names for this branch.

## Authorization / forbidden outcomes

FDP-79 does not record `forbidden` in the fraud-case read-model controller metric.

Authorization failures are rejected before these controller methods execute. FDP-79 observes controller-level read-model outcomes only.

Forbidden and security rejections remain owned by existing security and sensitive-read audit instrumentation. A future branch may add bounded security-denial telemetry, but it must not use caseId, path, principal, authority names, exception messages, or raw identifiers as labels.

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
