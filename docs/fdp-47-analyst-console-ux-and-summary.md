# FDP-47 Analyst Console UX and Summary Contract

## Scope

FDP-47 keeps the fraud-case work queue slice and the global fraud-case count as separate read contracts.

`GET /api/v1/fraud-cases/work-queue/summary` returns a point-in-time global count for analyst console navigation:

- `scope` is `GLOBAL_FRAUD_CASES`.
- `generatedAt` is the server-side timestamp for the count.
- `snapshotConsistentWithWorkQueue` is `false`.
- The count is not filter-scoped, not cursor-scoped, not page-scoped, and not used for pagination metadata.
- The count may differ from loaded work queue slices.

The summary endpoint is audited as `FRAUD_CASE_WORK_QUEUE_SUMMARY`. Its read-access audit result count is `1`, meaning one aggregate summary response, not the number of fraud cases.

## UI Rules

The analyst console labels the global count as all/global fraud cases. Loaded queue counters remain loaded-case counters and never imply a total queue size.

Summary loading errors are isolated to the summary UI. They must not block the fraud-case work queue, fraud transaction workspace, transaction scoring workspace, compliance workspace, or reports workspace.

## Non-Goals

FDP-47 does not add snapshot consistency between summary and queue slices, does not reintroduce exact count pagination for the work queue slice, does not add a legacy summary alias, and does not change mutation or idempotency semantics.
