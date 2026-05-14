# FDP-46 Fraud Case Work Queue UI

FDP-46 adds a read-only investigator work queue view to the analyst console. The source of truth is the FDP-45 backend read model exposed by `GET /api/v1/fraud-cases/work-queue`.

## Source Of Truth

- The UI uses `listFraudCaseWorkQueue()` and the `/api/v1/fraud-cases/work-queue` endpoint only.
- The existing `GET /api/v1/fraud-cases` list remains the fraud-case summary view and is not used for work queue semantics.
- Work queue filtering and sorting are backend-driven. The UI does not perform partial client-side work queue filtering.
- Header and work queue counters show loaded client slices only. They are not global totals unless the backend explicitly returns an aggregate in a future scope.

## Displayed Fields

The panel displays only the minimal `FraudCaseWorkQueueItemResponse` fields:

- `caseId`, `caseNumber`
- `status`, `priority`, `riskLevel`
- `assignedInvestigatorId`
- `createdAt`, `updatedAt`
- `caseAgeSeconds`, `lastUpdatedAgeSeconds`
- `slaStatus`, `slaDeadlineAt`
- `linkedAlertCount`

The UI does not render customer IDs, transaction details, amounts, merchant data, full linked alert IDs, audit internals, idempotency internals, raw reasons, or cursor values.

## Cursor Rules

`nextCursor` is opaque. The frontend stores it only in React state long enough to request the next slice. It is not parsed, decoded, logged, copied into the DOM, placed in the URL, or written to local/session storage. When filters, sort, or size change, the cursor and current content are cleared. Cursor requests do not include `page`.

Invalid cursor responses clear the current queue position and present a controlled refresh action for the first slice with the current filters.

## Filters And Sorting

Supported request fields are `size`, `cursor`, `status`, `priority`, `riskLevel`, `assignee`, `assignedInvestigatorId`, `createdFrom`, `createdTo`, `updatedFrom`, `updatedTo`, `linkedAlertId`, and `sort`.

The UI omits empty, null, undefined, and `ALL` filter values. Size is bounded to 100. Sort is explicit and selected from the FDP-45 allowlist:

- `createdAt,asc`, `createdAt,desc`
- `updatedAt,asc`, `updatedAt,desc`
- `priority,asc`, `priority,desc`
- `riskLevel,asc`, `riskLevel,desc`
- `caseNumber,asc`, `caseNumber,desc`

Work queue filters are draft-first. Changing a field does not issue a request until the analyst selects `Apply filters`. `Reset filters` clears the draft and loads the first slice. Applying or resetting filters clears cursor state and content before requesting a new first slice.

Date fields use the analyst's local `datetime-local` input and are serialized as UTC instants at the API-client boundary. Invalid local date values are rejected before fetch.

If a load-more response overlaps with already loaded case IDs, the UI de-duplicates rendered rows and shows `Queue changed while loading. Refresh from first slice.` The warning is cleared by refreshing from the first slice.

Sort labels are neutral (`Priority descending`, `Priority ascending`, `Risk descending`, `Risk ascending`) and document ordering direction only.

## Transaction Scoring Stream

The transaction scoring stream is also backend-filtered. The UI keeps filter edits as a draft and sends them only on `Apply filters`; it does not filter the current page locally.

Frontend guardrails:

- query length is capped at 128 characters;
- non-empty queries shorter than 3 characters are blocked before request state changes;
- page size remains bounded by the existing pagination control;
- no export, bulk action, raw query logging, or full-dataset client filtering is added.

Backend guardrails:

- `GET /api/v1/transactions/scored` bounds `size` to 100 and `page` to 1000;
- only `page`, `size`, `query`, `riskLevel`, and `classification` are accepted; unknown or duplicate single-valued params are rejected with `INVALID_FILTER`;
- blank query text is treated as absent;
- non-empty query text shorter than 3 characters is rejected with controlled `INVALID_FILTER` validation instead of widening to the unfiltered stream;
- query text longer than 128 characters is rejected without echoing the raw query;
- identifier-oriented filtering uses normalized indexed fields (`transactionIdSearch`, `customerIdSearch`, `merchantIdSearch`, `currencySearch`) populated at write time; matching uses quoted case-sensitive prefixes on normalized fields and exact normalized currency;
- raw case-insensitive regex over `transactionId`, `customerId`, `merchantInfo.merchantId`, or `transactionAmount.currency` is not used;
- filtered page totals are capped at 10,000 and must be treated as bounded navigation metadata, not an exact global count;
- filtering is documented as bounded search readiness, not full-text search, export, automated enumeration, or search-engine certification.

Sensitive scored-transaction reads continue through the read-access audit response advice. Audit targets store endpoint category `SCORED_TRANSACTION_SEARCH`, bounded page/size metadata, filter bucket, bounded result count, and a query hash rather than raw query values. Rejected validation reads are audited as `REJECTED`; unexpected query failures are audited as `FAILED`.

Scored-transaction metrics use only low-cardinality labels: endpoint family, outcome, filter bucket, and page-size sample. Metrics do not include raw query text, IDs, currency, exception messages, or actor identifiers. Repeated scored-transaction searches are sensitive-read activity; gateway or future service policy should enforce rate/misuse limits for high-volume production use. FDP-46 does not add bulk export or automated enumeration support.

## Error Handling

- `401`: sign-in required state, no stale success.
- `403`: displays `You do not have access to the fraud case work queue.`
- `400 INVALID_CURSOR` / `INVALID_CURSOR_PAGE_COMBINATION`: clears queue position and offers controlled refresh.
- `400 INVALID_FILTER` / `INVALID_SORT`: validation error, including unknown or duplicate scored-transaction query params, no legacy fallback.
- `503`: fail-closed unavailable state, no stale success or legacy fallback.
- Network errors expose a manual retry button and do not auto-loop.

## Non-Goals

FDP-46 does not add lifecycle mutation UI, assignment workflows, exports, bulk actions, idempotency changes, audit mutation changes, RegulatedMutationCoordinator routing, Kafka/outbox behavior, finality claims, or global exactly-once claims.

## Required Verification

The FDP-46 CI gate runs the frontend test suite and production build:

- `npm test -- --run`
- `npm run build`
- `mvn -B -pl alert-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=ScoredTransactionControllerValidationTest,ScoredTransactionSearchPolicyTest,TransactionMonitoringServiceSearchTest,ScoredTransactionDocumentMapperTest,ScoredTransactionSearchIndexReadinessTest,ReadAccessAuditClassifierTest,ReadAccessAuditEndpointTest,ReadAccessAuditServiceTest,ReadAccessAuditResponseAdviceTest,Fdp45ReadAccessAuditResponseAdviceMarkerTest,AlertServiceMetricsTest,AlertSecurityConfigTest" test`

Required tests cover endpoint selection, request parameter minimization, cursor privacy, invalid cursor handling, no sensitive field rendering, no mutation/export controls, load-more append behavior, duplicate case de-duplication, backend-driven filter/sort reload behavior, scored-transaction query validation, capped filtered totals, audited sensitive search outcomes, low-cardinality scored search metrics, authorization precedence, and stale transaction stream response protection.
