# Fraud Case Operations Runbook

Status: current operator runbook.

## Purpose

This runbook covers the current FraudCase work queue and regulated `PATCH` surface. FDP-81 removes the standalone
lifecycle handlers and their internal local lifecycle idempotency subsystem; there is no lifecycle idempotency record
or lifecycle idempotency dashboard to operate.

## Regulated Update Boundary

`PATCH /api/v1/fraud-cases/{caseId}` requires `X-Idempotency-Key` and runs through the shared regulated mutation
contract. Use the regulated mutation runbooks and dashboards for update recovery or evidence-state investigation.
Do not infer update state from removed lifecycle endpoints, collections or metrics.

## Work Queue Cursor Secret Rotation

This procedure covers rotation of `FRAUD_CASE_WORK_QUEUE_CURSOR_SIGNING_SECRET`.

Expected impact:

- Existing work queue cursors may become invalid after the signing secret changes.
- The API returns `INVALID_CURSOR` for cursors signed with the previous secret.
- Clients restart traversal without a cursor while preserving filters and sort.
- This is not data loss, not a lifecycle mutation failure, and not an audit failure.

Operator guidance:

1. Deploy the new secret through the configured secret manager or environment variable path.
2. Verify production-like profiles do not use local default cursor signing secrets.
3. Monitor the bounded `invalid_cursor` outcome.
4. Verify traversal by requesting the first work queue slice and following `nextCursor`.

The cursor is signed for integrity, not encrypted. Do not log cursor values or use cursor contents, query hashes,
identifiers or raw paths as metric labels or audit metadata.

Client guidance:

- Treat the cursor as opaque.
- Do not parse cursor values or cursor internals.
- Do not log cursor values.

## Sensitive Read Audit Unavailable

Work queue reads are sensitive fraud-case reads. In bank mode they fail closed when the required read-access audit
write is unavailable.
The read path does not guarantee exactly-once audit; it guarantees that no successful response is returned when
mandatory audit persistence is unavailable.

Symptoms:

- `GET /api/v1/fraud-cases/work-queue` returns `503`.
- Work queue failure metrics increase using bounded endpoint-family and outcome labels only.
- Logs describe read-access audit persistence failure with no raw identifiers.

Triage:

1. Check `alert-service` health and Mongo connectivity for read-access audit storage.
2. Check storage capacity, write concern failures, credentials and network policy.
3. Confirm that only bounded metric labels and audit metadata are emitted.
4. Restore audit persistence before retrying the work queue read.

Do not disable audit or bypass the fail-closed path. Do not add raw identifiers, raw query strings, cursors,
exception messages or stack traces to telemetry. This runbook does not claim external finality, global exactly-once execution, distributed
ACID, WORM storage, legal notarization or bank certification.

Alert name: `WorkQueueSensitiveReadAuditUnavailable`

Allowed low-cardinality labels:

- `endpoint_family`
- `outcome`
- `service`
- `environment`

Forbidden labels:

- `caseId`
- `actorId`
- `assignee`
- `linkedAlertId`
- `queryHash`
- raw path
- exception message
