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

## Runbook Link

Use `docs/runbooks/FDP-35-regulated-mutation-recovery-drill.md` for operator actions.

