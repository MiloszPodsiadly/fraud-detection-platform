# FDP-45 Sensitive Read Audit Unavailable Runbook

## Purpose

FDP-45 work queue reads are sensitive fraud-case reads. In bank mode the expected behavior is fail-closed when the
read-access audit write is unavailable. Do not disable audit to restore reads.

## Symptoms

- `GET /api/v1/fraud-cases/work-queue` or `GET /api/fraud-cases/work-queue` returns `503`.
- Work queue failure metrics increase with endpoint-family and outcome labels only.
- Application logs mention read-access audit persistence failure without raw identifiers.

## Expected Behavior

The service must fail closed for successful, rejected, and failed sensitive-read attempts when the mandatory audit write
cannot be recorded. The read path does not guarantee exactly-once audit; it guarantees that FDP-45 does not return a
successful sensitive read while the required audit write is unavailable.

## Triage

1. Check the alert-service health endpoint and Mongo connectivity for the audit collection.
2. Check storage capacity, write concern failures, credentials, and network policy for the audit store.
3. Verify the affected endpoint family and outcome labels without expanding metric cardinality.
4. Confirm that no lifecycle mutation, idempotency, Kafka/outbox, or transaction-boundary change was deployed as part
   of the response.

## What Not To Do

- Do not disable read-access audit.
- Do not bypass the fail-closed path.
- Do not add raw identifiers, raw query strings, exception messages, stack traces, assignees, or linked alert values to
  logs, metrics, or audit metadata.
- Do not claim external finality, global exactly-once delivery, distributed ACID, WORM storage, or bank certification.

## Recovery

Restore audit persistence first, then retry the work queue read. After recovery, verify that successful reads return
normally and that failed/rejected attempts still produce bounded audit metadata. Capture evidence using low-cardinality
metrics, deployment SHA, endpoint family, outcome, and timestamp windows only. No raw identifiers should be included in
the incident evidence package.
