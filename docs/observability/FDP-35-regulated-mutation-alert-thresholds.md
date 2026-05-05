# FDP-35 Regulated Mutation Alert Thresholds

All alert labels must remain low-cardinality. Do not include commandId, alertId, actorId, leaseOwner, idempotencyKey, requestHash, resourceId, exception message, raw path, token, or key material.

| Alert | Signal | Suggested threshold | Severity | Safe operator action | Forbidden action | Dashboard panel | Runbook |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Stale write rejection spike | `regulated_mutation_stale_write_rejected_total` rate | > 5/min for 10m | Warning | Inspect current owner and recent deploys | Rewrite lease owner | Stale worker/fencing | `docs/runbooks/FDP-35-regulated-mutation-recovery-drill.md` |
| Expired lease rejection spike | stale rejection reason `EXPIRED_LEASE` | > 3/min for 10m | Warning | Inspect long-running PROCESSING and host latency | Increase lease blindly | Long-running PROCESSING | same |
| Renewal budget exceeded spike | `regulated_mutation_lease_renewal_budget_exceeded_total` | > 0 for bank/prod | Critical | Inspect affected command state and business evidence | Raise budget without review | Lease renewal/checkpoint adoption | same |
| Long-running PROCESSING | PROCESSING age bucket | > configured stuck threshold | Critical | Inspect renewal count, lease expiry, and backlog | Submit new idempotency key | Long-running PROCESSING | same |
| Pending external evidence age | `FINALIZED_EVIDENCE_PENDING_EXTERNAL` age | > agreed external SLA | Warning | Inspect outbox/evidence confirmation | Mark confirmed manually | Outbox/evidence pending | same |
| Recovery-required count | recovery backlog count | > 0 sustained 15m | Critical | Run recovery drill | Expose stale snapshot as success | Recovery states | same |
| Outbox confirmation unknown spike | `PUBLISH_CONFIRMATION_UNKNOWN` count/rate | > 0 sustained 15m | Critical | Use outbox recovery and dual control | Re-publish manually outside runbook | Outbox/evidence pending | same |
| No-progress checkpoint spike | `regulated_mutation_checkpoint_no_progress_total` | > 0 sustained 10m | Warning | Inspect checkpoint reason and lease budget | Treat renewal as business progress | Lease renewal/checkpoint adoption | same |

