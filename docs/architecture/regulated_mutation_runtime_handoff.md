# Regulated Mutation Runtime Handoff

Status: current runtime reference.

## Runtime

The regulated mutation runtime supports one model: `EVIDENCE_GATED_FINALIZE_V1`. There is no retired executor,
compatibility replay policy, null-version fallback, or runtime model selector. Every command must carry the current
model version and pass canonical intent, evidence precondition, lease fencing, and local transaction checks.

The local transaction persists the business mutation, transactional outbox record, local success audit evidence,
response snapshot, and finalize marker. External anchor readiness and Trust Authority signing readiness remain outside
that transaction and are reconciled asynchronously.

## Operator Handoff

Before deployment, run the persisted-model preflight. Missing, null, retired, and unknown model versions block startup.
The preflight is read-only: it identifies unsupported retained records but does not purge, rewrite, or migrate them.
Resolution requires an approved operator data procedure outside the application startup path.

For recovery, inspect by command id or idempotency-key hash, preserve redaction, and use the authorized bounded recovery
endpoint. Never edit command state, lease owner, snapshots, audit evidence, outbox records, or business aggregates by
hand. Recovery state wins over any stale response snapshot.

## References

- ADR: `docs/adr/evidence_gated_regulated_mutation_finalize.md`
- preconditions: `docs/architecture/evidence_gated_finalize_preconditions.md`
- state machine: `docs/architecture/evidence_gated_finalize_state_machine.md`
- response contract: `docs/api/evidence_gated_finalize_response_contract.md`
- checkpoint policy: `docs/architecture/regulated_mutation_safe_checkpoint_policy.md`
- recovery runbook: `docs/runbooks/regulated_mutation_recovery.md`
- test plan: `docs/testing/evidence_gated_finalize_test_plan.md`

This runtime is not distributed ACID and does not provide exactly-once Kafka delivery, WORM storage, legal
notarization, or external finality.
