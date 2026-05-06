# FDP-35 Regulated Mutation Alert Thresholds

All alert labels must remain low-cardinality. Do not use command id, alert id, actor id, lease owner, idempotency key, request hash, resource id, exception message, token, or path as metric labels.

Do not include command id, alert id, actor id, lease owner, idempotency key, request hash, resource id, exception message, token, or path in metric labels.

Priority mapping: `P2` means warning requires triage during the active support window; `P1` means critical incident response and incident-lead ownership.

Window notation: `5m` means 5 minutes, `10m` means 10 minutes, `15m` means 15 minutes, and escalation to rollback review must happen by 30 minutes when recovery backlog or false-success ambiguity grows.

| Signal | Metric | P2 warning | P1 critical | Window | Operator action |
| --- | --- | --- | --- | --- | --- |
| stale owner rejection spike | `regulated_mutation_fenced_write_rejected_total{reason="STALE_OWNER"}` or `regulated_mutation_stale_write_rejected_total{reason="STALE_LEASE_OWNER"}` | `> 5` | `> 20` | `5m` | Inspect recent deploys, current owner, and lease takeover timeline; do not rewrite `lease_owner`. |
| expired lease rejection spike | `regulated_mutation_lease_renewal_rejected_total{reason="EXPIRED_LEASE"}` or stale rejection reason `EXPIRED_LEASE` | `> 1` | `> 5` | `5m` | Inspect long-running PROCESSING and host latency; do not extend expired leases manually. |
| budget exceeded | `regulated_mutation_lease_renewal_budget_exceeded_total` | `> 0` | `>= 3` | warning `5m`, critical `15m` | Run recovery drill and inspect checkpoint adoption; do not increase budget blindly. |
| long-running PROCESSING | `regulated_mutation_processing_age_seconds` or backlog-derived age | `p95 > configured lease duration * 2` | `max age > min(max total lease duration, 10m)` | `10m` | Inspect renewal count vs state transitions; checkpoint renewal is not progress. |
| repeated takeover | `regulated_mutation_claim_takeover_total` | `> 3` | `> 10` | `10m` | Inspect worker restarts and latency; do not bypass fencing. |
| FINALIZE_RECOVERY_REQUIRED | `evidence_gated_finalize_recovery_required_total` | `> 0` | `>= 3` | warning `5m`, critical `15m` | Follow FDP-29/FDP-35 recovery runbook; do not mark evidence confirmed manually. |
| checkpoint renewal failure | `regulated_mutation_checkpoint_renewal_total{outcome="FAILED"}` | `> 0` | `> 5` | warning `5m`, critical `15m` | Investigate checkpoint reason and durable command state; no hidden retry-as-success. |
| checkpoint renewal treated as progress guard | dashboard panel: processing age vs renewal count | renewal count increases while no state transition for `> configured lease duration * 2` | renewal count increases while no state transition for `> min(max total lease duration, 10m)` | `10m` | Treat as stuck processing until proven otherwise. |
| inspection endpoint failures | `regulated_mutation_inspection_failed_total` or sensitive-read audit failure counter | `> 0` | any fail-closed audit persistence failure | `5m` | Verify audit persistence and recovery authority path before allowing inspection use. |

FDP-35 provides modeled restart/recovery proof in CI. It verifies durable post-crash command states, replay policy, recovery API behavior, and operator visibility. It does not claim real OS/JVM/container process-kill chaos unless an explicit real-chaos job is implemented and run.

True OS/JVM/container termination chaos remains future scope unless explicitly implemented.
