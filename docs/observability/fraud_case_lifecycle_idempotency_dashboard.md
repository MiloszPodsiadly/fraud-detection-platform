# Fraud Case Lifecycle Idempotency Dashboard

Status: FDP-44 dashboard specification.

## Purpose

The dashboard observes local fraud-case lifecycle idempotency outcomes. It is not an external finality dashboard, not a
FDP-29 finalize dashboard, not a lease-fencing dashboard, and not a global exactly-once dashboard.

## Panels

| Panel | Metric | Labels | Alert Direction |
| --- | --- | --- | --- |
| Lifecycle idempotency outcomes | `fraud_case_lifecycle_idempotency_total` | `outcome` only | Alert on sustained `failure` or `snapshot_too_large`. |
| Replay ratio | `fraud_case_lifecycle_idempotency_total{outcome="replay"}` / total | `outcome` only | Investigate unusual replay spikes. |
| Conflict ratio | `fraud_case_lifecycle_idempotency_total{outcome="conflict"}` / total | `outcome` only | Check client key reuse across different claims. |
| In-progress/race ratio | `outcome in ("in_progress","race_resolved")` | `outcome` only | Check concurrent same-key retries. |

## Label Policy

Only low-cardinality labels are allowed. Do not add actor ids, case ids, idempotency keys, idempotency key hashes,
request hashes, request payloads, exception class names, stack traces, paths, or lease owners as metric labels.

## Dashboard Notes

- A green dashboard means the local lifecycle idempotency path is behaving within the observed window.
- A green dashboard does not mean distributed ACID, Kafka/outbox exactly-once, WORM/legal notarization, bank
  certification, FDP-29 external finality, or deterministic concurrent response ordering.
- No duplicate mutation/audit/idempotency record is guaranteed only for same-key same-claim retries while the local
  idempotency record exists and Mongo transactional rollback is available.
- After retention and eventual Mongo TTL cleanup, retry with the same key may execute as a new local lifecycle
  operation.
