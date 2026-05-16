# Fraud Case Operations Runbook

Status: current operator runbook.

## Purpose

This runbook covers fraud-case lifecycle idempotency, work queue cursor secret rotation, and work queue sensitive-read
audit unavailability.

## Scope

In scope:

- local fraud-case lifecycle idempotency
- `X-Idempotency-Key` behavior for lifecycle mutations
- work queue cursor invalidation after cursor signing secret rotation
- fail-closed work queue behavior when mandatory sensitive-read audit is unavailable

Unsupported claims:

- This runbook does not claim global exactly-once.
- This runbook does not claim distributed ACID.
- This runbook does not claim Kafka/outbox exactly-once.
- This runbook does not claim FDP-29 external finality.
- This runbook does not claim lease fencing.
- This runbook does not claim legal notarization.
- This runbook does not claim bank certification.
- This runbook does not claim deterministic concurrent response timing or deterministic concurrent response ordering.
- This runbook does not provide WORM storage.

## Fraud-Case Lifecycle Idempotency

Fraud-case lifecycle mutations require `X-Idempotency-Key`. The implementation provides local fraud-case lifecycle
idempotency inside the active retention window.

Expected behavior:

- Same key, same claim replays the stored local response when a completed idempotency record exists.
- Same-key same-claim concurrent calls do not create duplicate mutation/audit/idempotency record inside the active
  local idempotency window.
- Identical concurrent response timing is not guaranteed. The loser may receive replay, conflict, or in-progress
  behavior depending on timing.
- After retention and eventual Mongo TTL cleanup, the same key may execute as a new local lifecycle operation.
- Unknown `DataAccessException` and unknown `TransactionSystemException` are treated conservatively and surfaced with
  bounded error handling.

Triage:

1. Identify the lifecycle operation and idempotency record by safe hash or command reference.
2. Check for `in_progress`, `race_resolved`, conflict, replay, and retention-window outcomes.
3. Verify no duplicate mutation/audit/idempotency record exists for the same local operation.
4. Check low-cardinality metrics only.

Recovery rules:

- Do not manually edit fraud-case lifecycle state.
- Do not mutate idempotency records to manufacture replay.
- Do not paste raw idempotency keys in tickets or dashboards.
- Retry only with the original client idempotency key unless an approved recovery procedure says otherwise.

## Work Queue Cursor Secret Rotation

This procedure covers rotation of `FRAUD_CASE_WORK_QUEUE_CURSOR_SIGNING_SECRET`.

Expected impact:

- Existing work queue cursors may become invalid after the signing secret changes.
- The API returns `INVALID_CURSOR` for cursors signed with the previous secret.
- Clients should restart traversal without a cursor while preserving filters and sort.
- This is not data loss, not a lifecycle mutation failure, and not an audit failure.

Operator guidance:

1. Deploy the new secret through the configured secret manager or environment variable path.
2. Verify production-like profiles do not use local default cursor signing secrets.
3. Monitor the low-cardinality `invalid_cursor` metric.
4. Verify normal traversal by requesting the first work queue slice, following `nextCursor`, and confirming the next
   slice succeeds.

Client guidance:

- Treat the cursor as opaque.
- Do not parse cursor fields.
- Do not parse cursor internals.
- Do not persist cursors long-term.
- Do not retry the same invalid cursor indefinitely.
- Do not log cursor values.

Security:

The cursor is signed for integrity, not encrypted. The encoded payload may contain traversal fields such as the last
sort value and last id. Do not use cursor values, query hashes, last values, or last ids as metric labels. Do not copy
cursor internals into read-access audit payloads.

## Sensitive Read Audit Unavailable

Purpose:

Work queue reads are sensitive fraud-case reads. In bank mode the expected behavior is fail-closed when the read-access
audit write is unavailable. Do not disable audit to restore reads.

Symptoms:

- `GET /api/v1/fraud-cases/work-queue` returns `503`.
- Work queue failure metrics increase with endpoint-family and outcome labels only.
- Application logs mention read-access audit persistence failure without raw identifiers.

Expected behavior:

The service must fail-closed for successful, rejected, and failed sensitive-read attempts when the mandatory audit write
cannot be recorded. The read path does not guarantee exactly-once audit; it guarantees that the work queue does not
return a successful sensitive read while the required audit write is unavailable.

Triage:

1. Check alert-service health and Mongo connectivity for the audit collection.
2. Check storage capacity, write concern failures, credentials, and network policy for the audit store.
3. Verify endpoint family and outcome labels without expanding metric cardinality.
4. Confirm no lifecycle mutation, idempotency, Kafka/outbox, or transaction-boundary change was deployed as part of
   the response.

What not to do:

- Do not disable read-access audit.
- Do not disable audit.
- Do not bypass the fail-closed path.
- Do not add raw identifiers, raw query strings, exception messages, stack traces, assignees, linked alert values, or
  cursor values to logs, metrics, audit metadata, tickets, or examples.
- Do not claim external finality, global exactly-once delivery, distributed ACID, WORM storage, or bank certification.

Recovery:

Restore audit persistence first, then retry the work queue read. After recovery, verify that successful reads return
normally and that failed/rejected attempts still produce bounded audit metadata. Capture evidence using low-cardinality
metrics, deployment SHA, endpoint family, outcome, and timestamp windows only. No raw identifiers should be included in
the incident evidence package.

Alert specification:

Alert name: `WorkQueueSensitiveReadAuditUnavailable`

Condition:

- Work queue failures with audit-unavailable outcome exceed the configured threshold over 5 minutes, or
- `503` rate on the work queue endpoint exceeds the configured threshold while audit-unavailable logs are present.

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

Runbook links and evidence should include the deploy SHA, service health dashboard, and this runbook.
