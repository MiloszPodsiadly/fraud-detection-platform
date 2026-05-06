# FDP-35 Regulated Mutation Dashboard Spec

All panels must use low-cardinality labels only. Forbidden labels include commandId, alertId, actorId, leaseOwner, idempotencyKey, requestHash, resourceId, exception message, raw path, token, and key material.

## Panels

1. Regulated mutation command states: counts by `state`, `execution_status`, and `mutation_model_version`.
2. Recovery states: `RECOVERY_REQUIRED`, `FINALIZE_RECOVERY_REQUIRED`, and degradation reason counts.
3. Stale worker and fencing: `regulated_mutation_stale_write_rejected_total`, `regulated_mutation_fenced_transition_total`, and stale rejection rate by bounded reason/state/model.
4. Lease renewal and checkpoint adoption: `regulated_mutation_lease_renewal_total`, `regulated_mutation_checkpoint_renewal_total`, `regulated_mutation_checkpoint_no_progress_total`, budget exceeded counts, renewal count distribution, and lease remaining at transition.
5. Long-running PROCESSING: age buckets, lease expiry windows, renewal count buckets, and commands approaching renewal budget.
6. Outbox/evidence pending: `FINALIZED_EVIDENCE_PENDING_EXTERNAL` age buckets, `PUBLISH_CONFIRMATION_UNKNOWN`, and outbox terminal failures.
7. API recovery response rate: recovery vs success response counts with no business identifiers.

## Required Threshold Overlays

Threshold overlays must match the concrete values in `FDP-35-regulated-mutation-alert-thresholds.md`.

- Stale owner rejection: warning `> 5 in 5m`, critical `> 20 in 5m`.
- Expired lease rejection: warning `> 1 in 5m`, critical `> 5 in 5m`.
- Renewal budget exceeded: warning `> 0 in 5m`, critical `> 3 in 15m`.
- Long-running PROCESSING: warning `p95 > configured lease duration * 2`, critical `max age > min(max total lease duration, 10m)`.
- Repeated takeover: warning `> 3 in 10m`, critical `> 10 in 10m`.
- FINALIZE_RECOVERY_REQUIRED: warning `> 0 in 5m`, critical `> 3 in 15m`.
- Checkpoint renewal failure: warning `> 0 in 5m`, critical `> 5 in 15m`.
- Inspection endpoint failures: warning `> 0 in 5m`, critical on any fail-closed audit persistence failure.

The checkpoint renewal panel must compare processing age against renewal count. Renewal count increasing without state transition is an investigation signal, not business progress.

FDP-35 provides modeled restart/recovery proof in CI. It verifies durable post-crash command states, replay policy, recovery API behavior, and operator visibility. It does not claim real OS/JVM/container process-kill chaos unless an explicit real-chaos job is implemented and run.

True OS/JVM/container termination chaos remains future scope unless explicitly implemented.

## Runbook Link

Use `docs/runbooks/FDP-35-regulated-mutation-recovery-drill.md` for operator actions.
