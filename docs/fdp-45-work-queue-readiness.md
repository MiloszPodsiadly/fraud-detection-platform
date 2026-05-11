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

## Sorting And Pagination

Supported sort fields are `createdAt`, `updatedAt`, `priority`, `riskLevel`, and `caseNumber`. Sort directions are `asc` and `desc`. The Mongo query path appends `_id ASC` as a deterministic tie-breaker.

Pagination is bounded to page numbers `>= 0` and page sizes `1..100`. Invalid page requests fail with
`INVALID_PAGE_REQUEST`. The dedicated work queue uses bounded slice pagination and does not perform an exact Mongo
count for broad queues.

## SLA Fields

SLA values are derived at read time only:

- `caseAgeSeconds` is based on `now - createdAt`.
- `lastUpdatedAgeSeconds` is based on `now - updatedAt`.
- `slaDeadlineAt` is based on `createdAt + configured work queue SLA`.
- `slaStatus` may be `WITHIN_SLA`, `NEAR_BREACH`, `BREACHED`, `NOT_APPLICABLE`, or `UNKNOWN`.

The SLA duration is explicitly configured by `app.fraud-cases.work-queue.sla` and must be a positive duration at
startup. Closed or resolved fraud cases are `NOT_APPLICABLE`. Missing timestamps are `UNKNOWN`. These values are not
persisted and do not mutate fraud-case state, audit records, idempotency records, assignment, priority, or status.
The derived SLA and age values are not persisted.

## Index Readiness

Existing indexed fields cover the FDP-45 query shape: `status`, `priority`, `riskLevel`, `assignedInvestigatorId`, `linkedAlertIds`, and `caseNumber`. Recommended operational indexes for larger datasets are compound indexes that align with the allowed filters plus stable sort fields, for example status or assignee with `createdAt` and `_id`.

These are readiness recommendations, not a claim that every production workload is fully optimized.

## Security And Observability

The work queue requires `FRAUD_CASE_READ`. Read-only users can read the queue but cannot perform fraud-case lifecycle
mutations. Fraud-case audit history still requires `FRAUD_CASE_AUDIT_READ`. Work queue reads are recorded through the
existing sensitive read-access audit path using bounded metadata only; metrics are not treated as audit evidence.

Metrics are low-cardinality:

- `fraud_case_work_queue_requests_total{endpoint_family,outcome}`
- `fraud_case_work_queue_query_total{outcome,sort_field}`
- `fraud_case_work_queue_page_size_bucket{endpoint_family}`

Success metrics are recorded only after the service returns and read-access audit succeeds. Unexpected query/service
failures record failure metrics. Metric labels must not include case ids, users, assignees, linked alert ids, raw filter
values, exception messages, stack traces, request hashes, or idempotency keys.

## Non-Goals

FDP-45 does not change lifecycle mutation semantics, idempotency semantics, audit mutation semantics, transaction boundaries, Kafka/outbox behavior, `RegulatedMutationCoordinator` routing, FDP-29 finality, global exactly-once guarantees, external finality, distributed ACID, export APIs, or bank certification claims.
