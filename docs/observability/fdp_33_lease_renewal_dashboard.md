# FDP-33 Lease Renewal Dashboard

Status: branch evidence and dashboard contract.

## Scope

FDP-33 renewal metrics make slow regulated mutation work visible without introducing high-cardinality labels. Renewal
can preserve ownership but cannot prove progress. A worker that keeps renewing without state progress is an
operational incident.

Renewal can preserve ownership but cannot prove progress.

## Required Dashboards

| Panel | Purpose |
| --- | --- |
| Renewal success rate | Show whether owner-fenced renewal attempts are accepted. |
| Renewal rejection rate by bounded reason | Separate expired lease, stale owner, and validation rejection modes. |
| Budget exceeded rate | Detect renewal budget exhaustion. |
| Expired lease rejection rate | Detect stale work that crossed lease validity. |
| Stale owner rejection rate | Detect fenced stale-worker writes. |
| processing duration p95/p99 | Show long-running processing before it becomes recovery pressure. |
| Lease remaining at transition p50/p95/p99 | Detect transitions happening near lease expiry. |
| Renewal count distribution | Bucket renewal counts only; do not label by command. |
| Finalized/recovery command count after renewal failure | Confirm recovery status wins over stale success snapshots. |

## Required Alerts

| Alert | Trigger direction |
| --- | --- |
| Renewal rejection rate spike | Investigate bounded rejection reason and current owner. |
| Budget exceeded rate | Alert when `> 0` for a sustained window. |
| Expired lease spike | Investigate host latency and long-running `PROCESSING`. |
| Stale owner spike | Confirm stale-worker fencing is active. |
| Processing duration p99 | Alert when `processing_duration_p99 > 70% of lease budget`. |
| commands renewing but not progressing | Treat as stuck processing until state movement is visible. |
| `FINALIZE_RECOVERY_REQUIRED` after renewal failures | Follow regulated mutation recovery runbook. |

## Forbidden Labels

Do not add any of these values as metric labels:

- command id
- alert id
- actor id
- lease owner
- idempotency key
- request hash
- resource id
- exception message
- raw path
- token

## Operator Interpretation

Renewal success is not a success signal for the mutation. It means only that the current owner kept its lease inside
the configured budget. Operators should correlate renewal count, state progress, processing duration, stale-owner
rejections, expired-lease rejections, and budget-exceeded recovery counts before changing configuration.

This dashboard does not enable production or bank behavior by itself. It also does not provide a distributed lock,
external finality, or exactly-once Kafka delivery.
