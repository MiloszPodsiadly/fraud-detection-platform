# FDP-45 Fraud Case Work Queue Readiness

FDP-45 hardens the fraud-case investigator work queue as a bounded read model on the existing fraud-case query path. The authoritative list and search semantics remain `FraudCaseQueryService` plus `FraudCaseSearchRepository`; FDP-45 does not add a second search subsystem.

## Public list contract compatibility

`GET /api/v1/fraud-cases` and legacy `GET /api/fraud-cases` keep returning `PagedResponse<FraudCaseSummaryResponse>`.
FDP-45 does not change that existing public list/search contract.

## Dedicated work queue contract

`GET /api/v1/fraud-cases/work-queue` and legacy `GET /api/fraud-cases/work-queue` return
`FraudCaseWorkQueueSliceResponse`, a bounded slice without exact `totalElements` or `totalPages`. The slice contains
`FraudCaseWorkQueueItemResponse`, a minimal read model for queue scanning:

- `caseId`, `caseNumber`
- `status`, `priority`, `riskLevel`
- `assignedInvestigatorId`
- `createdAt`, `updatedAt`
- derived `caseAgeSeconds`, `lastUpdatedAgeSeconds`
- derived `slaStatus`, `slaDeadlineAt`
- `linkedAlertCount`

The work queue response intentionally does not include full linked alert ids, transaction details, audit internals,
idempotency records, raw persistence-only fields, or lifecycle mutation payloads.

## Filters

Supported filters are allowlisted: `status`, `priority`, `riskLevel`, `assignee` or `assignedInvestigatorId`, `createdFrom`, `createdTo`, `updatedFrom`, `updatedTo`, and `linkedAlertId`.

Unknown filters are rejected. Duplicate single-valued query params such as two `status` or `sort` values are rejected.
Date ranges where `from` is after `to` are rejected. FDP-45 does not add regex, free-text search, customer-id search,
export, or unbounded list-all behavior.

String filters are bounded before query construction. `assignee`, `assignedInvestigatorId`, and `linkedAlertId` are
limited to 128 characters, while `sort` is limited to 64 characters and `cursor` is limited to 2048 characters. `assignee` and `assignedInvestigatorId` are
trimmed before comparison; blank values are treated as absent, and non-blank mismatches are rejected. The comparison is
case-sensitive and does not apply identity aliasing beyond trimming.

## Sorting And Pagination

Supported sort fields are `createdAt`, `updatedAt`, `priority`, `riskLevel`, and `caseNumber`. Sort directions are `asc` and `desc`. The Mongo query path appends `_id ASC` as a deterministic tie-breaker. `priority` and `riskLevel` sorting uses stored enum value order; FDP-45 does not claim a custom business severity ranking.

Pagination is bounded to page numbers `0..1000` and page sizes `1..100`. Invalid page requests fail with
`INVALID_PAGE_REQUEST` before repository access. The dedicated work queue uses bounded slice pagination and does not perform an exact Mongo count for broad queues.

Cursor/keyset pagination is the recommended work queue traversal path. Requests may pass `cursor=<opaque cursor>`
returned by the previous response. The cursor is signed, versioned, and bound to the canonical query shape: normalized
filters plus sort field/direction. Changing filters or sort while presenting an existing cursor fails closed with
`INVALID_CURSOR`; clients must restart traversal without a cursor when changing filters or sort. The cursor payload
contains a signed query hash, but clients must not parse it and operators must not log the cursor, query hash, last
value, or last id. Unsupported versions, malformed values, tampering, filter mismatches, or sort mismatches fail closed
with `INVALID_CURSOR`. Cursor mode uses a keyset predicate and does not call Mongo `skip(...)`.

Cursor mode and page mode are separate traversal contracts. `cursor` with missing/default `page=0` is accepted, but
`cursor` with any non-zero page fails closed with `INVALID_CURSOR_PAGE_COMBINATION`. Size is not part of the cursor query hash. Clients may change `size` within `1..100`, although stable traversal is easiest when clients keep size unchanged between cursor requests.

Page mode remains as bounded offset compatibility mode for exploratory use. Repeated requests near
`MAX_PAGE_NUMBER=1000` should be treated as an operational signal to refine filters or use cursor traversal.
Low-cardinality alerting can watch for high-page work queue usage by endpoint family and outcome, but must not label by
case id, assignee, linked alert id, raw query string, request hash, or cursor value.

The legacy list endpoints keep their `PagedResponse<FraudCaseSummaryResponse>` compatibility contract and therefore
still perform exact count pagination. FDP-45 aligns their API boundary with the same `0..1000` and `1..100` bounds, but
the legacy exact count remains compatibility debt. High-volume investigator queues should use the dedicated work queue
slice endpoint with cursor traversal. A future hardening item may move the legacy list contract to cursor/keyset
pagination or a capped count after a versioned public API decision.

## Release Notes

### Legacy list pagination bound

`GET /api/v1/fraud-cases` and legacy `GET /api/fraud-cases` now reject `page > 1000`. This is an intentional abuse
prevention safety boundary for the existing `PagedResponse` compatibility path. Deep operational browsing should use
`/api/v1/fraud-cases/work-queue` or legacy `/api/fraud-cases/work-queue` with filters and cursor pagination.

## SLA Fields

SLA values are derived at read time only:

- `caseAgeSeconds` is based on `now - createdAt`.
- `lastUpdatedAgeSeconds` is based on `now - updatedAt`.
- `slaDeadlineAt` is based on `createdAt + configured work queue SLA`.
- `slaStatus` may be `WITHIN_SLA`, `NEAR_BREACH`, `BREACHED`, `NOT_APPLICABLE`, or `UNKNOWN`.

The SLA duration is explicitly configured by `app.fraud-cases.work-queue.sla` and must be a positive duration at
startup. Local development may use the `application.yml` fallback value, but production-like profiles such as `prod` and
`bank` require the `FRAUD_CASE_WORK_QUEUE_SLA` environment variable without a default. Cursor signing also requires
`FRAUD_CASE_WORK_QUEUE_CURSOR_SIGNING_SECRET` in `prod` and `bank`; local development may use the `application.yml`
fallback only. Production-like profiles reject known local cursor signing secrets. Rotating the cursor signing secret
can invalidate existing cursors, so clients should restart traversal without cursor after rotation. SLA is business policy, not persistence state. Closed or resolved fraud cases are `NOT_APPLICABLE`.
Missing timestamps are `UNKNOWN`. These values are not persisted and do not mutate fraud-case state, audit records,
idempotency records, assignment, priority, or status. The derived SLA and age values are not persisted.

## Index Readiness

Existing indexed fields cover the FDP-45 query shape: `status`, `priority`, `riskLevel`, `assignedInvestigatorId`,
`linkedAlertIds`, `caseNumber`, `createdAt`, and `updatedAt`. Required proof checks that every allowlisted stable sort
field has an index. FDP-45 ships compound index readiness for common query patterns aligned with the allowed filters
plus stable sort fields:

- `status + createdAt + _id`
- `assignedInvestigatorId + createdAt + _id`
- `priority + createdAt + _id`
- `riskLevel + createdAt + _id`
- `linkedAlertIds + createdAt + _id`
- `status + updatedAt + _id`
- `assignedInvestigatorId + updatedAt + _id`

FDP-45 ships minimum allowed-sort index readiness plus these compound index definitions in `FraudCaseDocument`. This is
not a claim that every filter/sort combination is production-optimized.

## Security And Observability

The work queue requires `FRAUD_CASE_READ`. Read-only users can read the queue but cannot perform fraud-case lifecycle
mutations. Fraud-case audit history still requires `FRAUD_CASE_AUDIT_READ`. Work queue reads are recorded through the
existing sensitive read-access audit path using bounded metadata only; metrics are not treated as audit evidence.
Successful reads are audited as `SUCCESS`. Validation rejections such as unsupported filters, duplicate params, invalid
ranges, overlong filters, or deep page requests are audited as `REJECTED`. Unexpected service or repository failures are
audited as `FAILED`. These audit attempts use endpoint/resource metadata and result count only; raw filters and user
supplied values are not stored.

For `/api/v1/fraud-cases/work-queue` and legacy `/api/fraud-cases/work-queue`, the controller owns the sensitive-read
audit for `SUCCESS`, `REJECTED`, and `FAILED` outcomes because FDP-45 needs explicit outcome classification. The generic
response advice remains the owner for other sensitive reads when no manual audit has happened. `AUDITED_ATTRIBUTE`
prevents response-advice duplicate writes after a manual work queue audit. Manual work queue audit preserves bounded
query metadata such as query hash, page, and size; it does not store raw query strings, raw assignees, linked alert ids,
case-id lists, exception messages, or stack traces.

Rejected work queue reads normally return `400`. In fail-closed sensitive-read audit mode, if audit persistence is
unavailable, rejected reads, failed reads, and successful reads return `503` instead. This is intentional: sensitive-read
audit is mandatory in bank mode, and success metrics are recorded only after the read succeeds and the required audit
write succeeds. The sensitive-read audit unavailable runbook is `docs/runbooks/fdp-45-sensitive-read-audit-unavailable.md`.

Metrics are low-cardinality:

- `fraud_case_work_queue_requests_total{endpoint_family,outcome}`
- `fraud_case_work_queue_query_total{outcome,sort_field}`
- `fraud_case_work_queue_page_size_bucket{endpoint_family}`

Success metrics are recorded only after the service returns and read-access audit succeeds. Unexpected query/service
failures record failure metrics. Cursor misuse, tampering, filter/sort mismatch, or cursor/page conflicts record the
low-cardinality `invalid_cursor` outcome. Metric labels must not include case ids, users, assignees, linked alert ids,
raw filter values, raw query strings, cursor values, query hashes, last cursor values, last cursor ids, exception
messages, stack traces, request hashes, or idempotency keys.

Operational alerting for audit-unavailable fail-closed behavior is specified in
`docs/runbooks/fdp-45-sensitive-read-audit-unavailable.md` as `WorkQueueSensitiveReadAuditUnavailable`. Metrics are not
audit evidence.

## Non-Goals

FDP-45 does not change lifecycle mutation semantics, idempotency semantics, audit mutation semantics, transaction boundaries, Kafka/outbox behavior, `RegulatedMutationCoordinator` routing, FDP-29 finality, global exactly-once guarantees, external finality, distributed ACID, export APIs, or bank certification claims.

## Merge Gate

FDP-45 is GO only when the current head SHA has all required CI jobs completed successfully, including backend,
FDP-42, FDP-43, FDP-44, regulated mutation regression, and the FDP-45 work queue proof suite. The FDP-45 proof suite
must include contract compatibility, pagination bounds, duplicate-param rejection, filter normalization/length checks,
allowlisted sorting, cursor/keyset traversal, query binding, page/cursor conflict rejection, tamper rejection, profile
secret safety, invalid-cursor observability, index readiness, real Mongo filter/sort/page proof,
SLA config/derived fields, read-only safety, security, low-cardinality metrics, read-access audit
success/rejected/failed outcomes, no duplicate work queue success audit, response-advice marker behavior, fail-closed
audit precedence, OpenAPI truth, and no-overclaim docs proof.

FDP-45 is NO-GO while any required job is pending, in progress, skipped, missing, cancelled, timed out, or failed. It is
also NO-GO if the old `GET /api/v1/fraud-cases` contract drifts, the work queue performs unbounded list/export/exact
count behavior, unsupported filters silently fall back, failed sensitive-read attempts are not audited, successful work
queue reads are audited twice, or docs claim mutation/idempotency/finality guarantees outside FDP-45 scope.
