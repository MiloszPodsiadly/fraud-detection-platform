# Fraud Case Evidence Summary

Status: current product-domain source of truth.

## Purpose

The fraud-case evidence summary is a read-only investigation context projection for an existing fraud case.
It summarizes bounded evidence snapshot metadata from alerts linked to the case so an analyst-facing client can show
why the case has supporting signal context without loading full alert details.

The projection is generated at read time. It does not persist a new document, create or edit evidence, mutate case
lifecycle state, publish Kafka events, or create analyst decisions.

## Scope Boundary

FDP-73 v1 summarizes evidence from alerts linked to the FraudCase.

Relationship source:
`FraudCaseDocument.linkedAlertIds`

Evidence source:
`AlertDocument.evidenceSnapshot`

Direct SuspiciousTransaction aggregation is intentionally out of scope for this first slice.
SuspiciousTransaction-derived context may appear only if it has already been materialized into the linked alert
evidence snapshot.

Future branches may add direct SuspiciousTransaction evidence aggregation only after FraudCase has a stable,
authorized, audited relationship to suspicious transactions.

## Data Sources

- `FraudCaseDocument.linkedAlertIds` is the only relationship source.
- `AlertDocument.evidenceSnapshot` is the only evidence input.
- The response uses only bounded evidence fields: reason code, evidence type, severity, source, status, and
  enum-derived product copy for title and description.
- Raw alert ids, transaction ids, customer ids, account identifiers, correlation ids, source event ids, feature
  snapshots, model payloads, score details, raw evidence title or description text, and raw evidence attributes are
  not response fields.

## Endpoint

`GET /api/v1/fraud-cases/{caseId}/evidence-summary`

The endpoint requires `fraud-case:read`. Alert read authority and SuspiciousTransaction read authority are not
substitutes. Missing authentication returns `401`; missing authority returns `403`; a missing case returns the current
fraud-case not-found response.

Successful reads are sensitive-read audited with the fraud-case id as the controlled resource id and a bounded result
count. Missing fraud cases are audited as `REJECTED` controlled not-found attempts. Unexpected backend failures are
audited as `FAILED`. Ordinary logs and metrics must not include linked alert ids, customer or account identifiers,
transaction ids, correlation ids, query payloads, raw evidence attributes, or raw backend exception payloads.

## Aggregation Semantics

- At most 100 linked alerts are included in one projection.
- If the linked-alert limit is exceeded, the response sets `truncated=true`,
  `truncationReason=LINKED_ALERT_LIMIT_EXCEEDED`, and `partial=true`.
- If any linked alert in the included source window is missing, or the source window is truncated, the aggregate
  evidence status is `PARTIAL` unless an `ERROR` evidence status dominates.
- Empty linked-alert context is treated as legacy case context and returns aggregate status `LEGACY`.
- Non-legacy linked-alert context with no evidence returns aggregate status `UNAVAILABLE`.
- `ERROR` evidence status dominates the aggregate status.
- Any `PARTIAL`, `LEGACY`, `STALE`, `UNAVAILABLE`, or `NOT_APPLICABLE` evidence status makes the aggregate status
  `PARTIAL` unless an `ERROR` is present.
- `AVAILABLE` is returned only when at least one evidence item exists and every included evidence item is
  `AVAILABLE` and source coverage is complete.
- Top reason codes are derived from existing canonical reason-code strings. The projection does not invent an
  `UNKNOWN` reason code.
- Highest-severity evidence is deterministic and bounded.
- `generatedAt` is the read projection time, not an evidence event time or case outcome time.

## Non-Claims

This projection is not fraud confirmation, not a case decision, not a final outcome, not an analyst disposition, not
legal proof, and not a complete investigation view. It is not a replacement for full alert review, case audit history,
or lifecycle policy. It does not aggregate all suspicious transactions and is not complete case evidence.
