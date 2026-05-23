# FDP-76 - Fraud Case Evidence Timeline Read Model

Status: current product contract for the backend read model.

## Purpose

FDP-76 adds a bounded, read-only fraud-case evidence timeline projection derived from existing read-safe case and
linked-alert evidence data.

Core rule:

- Timeline explains available chronology.
- Timeline does not create history.
- Timeline does not claim completeness.
- Timeline does not decide.
- Timeline does not mutate.
- Timeline does not prove.

## Endpoint

`GET /api/v1/fraud-cases/{caseId}/evidence-timeline`

Authority:
`FRAUD_CASE_READ`

`ALERT_READ` and `SUSPICIOUS_TRANSACTION_READ` are not substitutes.

The request uses only the `caseId` path variable. It does not accept query selectors, request body selectors, alert id,
suspicious transaction id, transaction id, customer id, or account id.

## Scope IN

- backend read-only endpoint
- FraudCase and linked Alert read-safe data
- bounded DTO
- deterministic ordering
- missing timestamp handling
- `approximateTime`
- partial, legacy, and truncated states
- sensitive-read audit
- docs and tests

## Scope OUT

- no UI
- no frontend
- no mutation
- no case lifecycle changes
- no analyst decision changes
- no final outcome
- no Kafka
- no event store
- no audit trail
- no legal record
- no lifecycle history
- no workflow history reconstruction
- no raw payloads
- no raw identifiers in event DTO

## Non-Claims

This timeline is not:

- audit trail
- legal record
- complete case history
- complete lifecycle timeline
- analyst decision history
- fraud proof
- fraud verdict
- final outcome
- event store

It is a derived read projection. It must not be used as source-of-truth history or evidence of lifecycle completion.

## Event Types v1

- `FRAUD_CASE_CREATED`
- `FRAUD_ALERT_LINKED`
- `ALERT_EVIDENCE_SNAPSHOT_AVAILABLE`
- `ALERT_EVIDENCE_SNAPSHOT_PARTIAL`
- `ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE`
- `LEGACY_CONTEXT`

Deferred:

- `CASE_STATUS_CHANGED`
- `ANALYST_DECISION_RECORDED`

Only add deferred event types if an explicit timestamped read-safe source exists. Current status, `updatedAt`,
`decisionReason`, `decisionTags`, `closedAt`, and current decision fields do not create lifecycle or analyst decision
history in FDP-76.

## Data Safety

- `eventKey` is synthetic and bounded.
- `eventKey` is not a domain identifier.
- No linked entity id is returned.
- No alert IDs are returned.
- No transaction IDs are returned.
- No customer or account IDs are returned.
- No correlation IDs are returned.
- No source event IDs are returned.
- No evidence IDs are returned.
- No score decision IDs are returned.
- No raw title or description is returned.
- No model payload is returned.
- No event payload is returned.

Titles and descriptions are generated bounded product copy derived from event type, source, and status only.

## Timestamp Safety

- Missing timestamps do not crash the endpoint.
- Fallback or unknown timestamp marks `approximateTime=true`.
- `partial=true` when ordering is incomplete.
- `generatedAt` is the read response generation time and is not used as `occurredAt`.
- Events sort by `occurredAt` ascending, nulls last, fixed event-type priority, then deterministic local ordinal.

## Boundedness

`MAX_TIMELINE_EVENTS` is 100.

If the derived timeline exceeds the limit:

- `truncated=true`
- `partial=true`
- `truncationReason=TIMELINE_EVENT_LIMIT_EXCEEDED`
- `events.size() <= MAX_TIMELINE_EVENTS`

Missing linked alerts set `partial=true`, do not create fake events, and do not expose missing alert identifiers.
Legacy cases return bounded legacy context when no structured linked-alert evidence timeline data exists.

## Sensitive-Read Audit

Successful reads are audited as:

- endpoint category: `FRAUD_CASE_EVIDENCE_TIMELINE`
- resource type: `FRAUD_CASE`
- resource id: `caseId`
- result count: `events.size()`

Missing fraud cases are audited as `REJECTED`. Unexpected runtime failures are audited as `FAILED`.

Linked alert IDs, transaction IDs, customer IDs, account IDs, correlation IDs, source event IDs, evidence IDs, score
decision IDs, raw payloads, and raw exception messages are not used as audit resource IDs.

## Merge Gate

- Backend-only.
- No UI.
- No frontend changes.
- Read-only endpoint.
- No mutation.
- No Kafka.
- No event store.
- No new authority.
- `GET /api/v1/fraud-cases/{caseId}/evidence-timeline`.
- `FRAUD_CASE_READ` required.
- `ALERT_READ` alone is insufficient.
- `SUSPICIOUS_TRANSACTION_READ` alone is insufficient.
- No query selectors.
- No request body.
- Timeline is a derived read projection, not source-of-truth history.
- No audit trail claim.
- No legal record claim.
- No complete lifecycle history claim.
- No workflow history reconstruction.
- No `CASE_STATUS_CHANGED` from current status or `updatedAt`.
- No `ANALYST_DECISION_RECORDED` from `decisionReason` or current fields.
- No event invented without source data.
- `eventKey` is synthetic and bounded.
- `eventKey` does not expose raw domain IDs.
- No `linkedEntityId`, `alertId`, `transactionId`, `customerId`, `accountId`, `correlationId`, `sourceEventId`,
  `evidenceId`, `scoreDecisionId`, raw payload, raw evidence title or description, final outcome, analyst decision,
  proof, or verdict fields.
- Deterministic ordering.
- Stable tie-breakers.
- Missing timestamps do not crash.
- Missing timestamps mark `approximateTime=true` and `partial=true`.
- `generatedAt` is not used as `occurredAt`.
- `MAX_TIMELINE_EVENTS` enforced.
- Truncation sets `truncated=true`, `partial=true`, and `truncationReason=TIMELINE_EVENT_LIMIT_EXCEEDED`.
- Missing linked alerts set `partial=true`.
- Missing linked alerts do not create fake events.
- Legacy cases are safe.
- Success, missing case, and unexpected failure are audited.
- Audit resource is `FRAUD_CASE` with `caseId`.
- No linked alert IDs as audit resource IDs.
- No repository save.
- No regulated mutation coordinator.
- No evidence creation or editing.
- No event publishing.
- No outbox writes.
