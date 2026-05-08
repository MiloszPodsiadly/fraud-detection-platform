# FDP-33 Lease Renewal Dashboard

FDP-33 renewal metrics must make slow regulated mutation work visible without introducing high-cardinality labels. Renewal can preserve ownership but cannot prove progress. A worker that keeps renewing without state progress is an operational incident.

## Required Dashboards

- renewal success rate
- renewal rejection rate by bounded reason
- budget exceeded rate
- expired lease rejection rate
- stale owner rejection rate
- processing duration p95/p99
- lease remaining at transition p50/p95/p99
- renewal count per command distribution, bucketed only
- finalized/recovery command count after renewal failure

## Required Alerts

- renewal rejection rate spike
- budget exceeded rate > 0 for sustained window
- expired lease spike
- stale owner spike
- processing_duration_p99 > 70% of lease budget
- commands renewing but not progressing
- FINALIZE_RECOVERY_REQUIRED increase after renewal failures

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

Renewal success is not a success signal for the mutation. It means only that the current owner kept its lease inside the configured budget. Operators should correlate renewal count, state progress, processing duration, stale-owner rejections, expired-lease rejections, and budget-exceeded recovery counts before changing configuration.
