# FDP-29 Local Audit Chain Contention Runbook

This runbook applies to the disabled-by-default FDP-29 submit-decision local evidence-precondition-gated finalize path.

## Symptom

- increased `fdp29_local_audit_chain_retry_total`
- increased `fdp29_local_audit_chain_append_total{outcome="CHAIN_CONFLICT_EXHAUSTED"}`
- submit-decision requests fail before visible finalize or enter explicit recovery state

## Meaning

The local `SUCCESS` audit phase writer could not append to the single local audit-chain partition within the configured retry budget. The writer is bounded and fail-closed for the finalize transaction. It must not silently skip local success evidence or continue with a best-effort chain head.

## Runtime Configuration

- `app.audit.local-phase-writer.max-append-attempts`
- `app.audit.local-phase-writer.backoff-ms`
- `app.audit.local-phase-writer.max-total-wait-ms`
- `app.audit.local-phase-writer.allow-long-total-wait`

For FDP-29 enablement, attempts, backoff, and total wait must be positive. Total wait must be at most `5000` ms unless `allow-long-total-wait=true` is explicitly set. Defaults are intentionally bounded for local transaction safety.

## Metrics

Monitor:

- `fdp29_local_audit_chain_append_total{outcome="SUCCESS"}`
- `fdp29_local_audit_chain_append_total{outcome="DUPLICATE_PHASE"}`
- `fdp29_local_audit_chain_append_total{outcome="CHAIN_CONFLICT_RETRY"}`
- `fdp29_local_audit_chain_append_total{outcome="CHAIN_CONFLICT_EXHAUSTED"}`
- `fdp29_local_audit_chain_append_total{outcome="AUDIT_INSERT_FAILED"}`
- `fdp29_local_audit_chain_append_total{outcome="ANCHOR_INSERT_FAILED"}`
- `fdp29_local_audit_chain_append_total{outcome="LOCK_RELEASE_FAILED"}`
- `fdp29_local_audit_chain_retry_total{reason="LOCK_CONFLICT"}`
- `fdp29_local_audit_chain_retry_total{reason="DUPLICATE_KEY"}`
- `fdp29_local_audit_chain_append_duration_ms`
- `fdp29_local_audit_chain_lock_release_failure_total`

Labels are intentionally low-cardinality. They must not include command ids, audit ids, alert ids, actors, idempotency keys, lock owner tokens, paths, or exception messages.

## Safe Operator Response

1. Check whether contention is transient by comparing retry counts to successful append counts.
2. Confirm Mongo primary health and latency.
3. Inspect whether stale locks expire normally after the configured lock TTL.
4. Confirm unique audit-chain indexes exist for `audit_events` and `audit_chain_anchors`.
5. Tune retry settings only within the bounded startup guard policy.
6. Keep FDP-29 flags disabled or roll them back if contention prevents safe local evidence append.

## Forbidden Actions

- Do not disable the local `SUCCESS` audit writer while FDP-29 finalize is enabled.
- Do not remove unique chain indexes.
- Do not increase retry settings without an explicit bounded wait review.
- Do not treat `CHAIN_CONFLICT_EXHAUSTED` as a successful local finalize.
- Do not manually edit audit chain position or local anchor records.

## Recovery Notes

If a command reaches `FINALIZE_RECOVERY_REQUIRED`, use the separate FDP-29 finalize recovery runbook. Contention metrics are operational signals; they are not compliance evidence by themselves.
