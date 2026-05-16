# Regulated Mutation Recovery Runbook

Status: current operator runbook.

## Purpose

This runbook covers regulated mutation lease renewal, safe checkpoint renewal, local evidence-gated finalize recovery,
and local audit-chain contention. It replaces branch-specific FDP runbook files with one current operational procedure.

## Scope

In scope:

- stale or expired regulated mutation leases
- checkpoint renewal failure
- renewal budget exhaustion
- `FINALIZE_RECOVERY_REQUIRED`
- local audit-chain contention in the evidence-gated finalize path
- recovery visibility and bounded recovery actions

Out of scope:

- public heartbeat APIs
- manual command state rewrites
- business progress claims from lease renewal
- production enablement, bank certification, or external finality

This runbook does not provide WORM storage, legal notarization, distributed ACID, or exactly-once Kafka delivery.

## Common Rules

- Renewal preserves ownership, not business progress.
- Checkpoint failure means the worker must stop.
- Recovery state wins over progress-looking fields.
- Operators must inspect durable command state before acting.
- Use command id or idempotency hash. Do not paste raw idempotency keys in tickets, logs, runbooks, or dashboards.
- Manual state repair, rollback approval, feature flag disablement, and renewal budget changes require dual control.

## Required Authority

- Read-only inspection: `regulated-mutation:recover` or `audit:verify`
- Recovery execution: `regulated-mutation:recover`
- Audit degradation resolution: authority documented by the audit degradation endpoint

## Lease Renewal Reasons

| Reason | Meaning | Safe action | Forbidden action |
| --- | --- | --- | --- |
| `STALE_OWNER` | Durable owner differs from worker token. | Confirm newer owner and let current owner or recovery proceed. | Do not rewrite `lease_owner`. |
| `EXPIRED_LEASE` | Lease expired before claimed transition. | Inspect command state, takeover timeline, Mongo latency, and recovery backlog. | Do not mark success from stale output. |
| `BUDGET_EXCEEDED` | Renewal budget exhausted. | Treat as recovery-required or fail-closed; inspect degradation reason. | Do not increase budget without dual approval. |
| `INVALID_EXTENSION` | Requested extension is outside policy. | Check config drift and deployment timeline. | Do not bypass renewal policy. |
| `COMMAND_NOT_FOUND` | Command no longer exists or lookup is wrong. | Verify command id/hash and audit trail. | Do not create replacement commands manually. |
| `NON_RENEWABLE_STATE` | State must not renew. | Respect current terminal or recovery state. | Do not renew manually. |
| `TERMINAL_STATE` | Command is already terminal. | Use replay result only and verify aggregate consistency. | Do not mutate terminal command fields. |
| `RECOVERY_STATE` | Command already requires recovery. | Follow recovery endpoint or recovery owner path. | Do not replay stale snapshot as success. |
| `MODEL_VERSION_MISMATCH` | Worker model version differs from stored command. | Inspect deployment and route to matching executor/recovery owner. | Do not downgrade `mutation_model_version`. |
| `EXECUTION_STATUS_MISMATCH` | Expected execution status no longer matches. | Re-read command and retry only through normal coordinator path. | Do not force status to `COMPLETED`. |
| `UNKNOWN` | Guard returned an unclassified reason. | Treat as platform incident and inspect logs without raw payloads. | Do not add ad hoc recovery behavior. |

## Safe Checkpoint Coverage

Approved checkpoints covered by this runbook:

- `BEFORE_ATTEMPTED_AUDIT`
- `BEFORE_LEGACY_BUSINESS_COMMIT`
- `BEFORE_SUCCESS_AUDIT_RETRY`
- `BEFORE_EVIDENCE_PREPARATION`
- `AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE`
- `BEFORE_EVIDENCE_GATED_FINALIZE`

Checkpoint-specific rules:

| Checkpoint failure | Safe action | Forbidden action |
| --- | --- | --- |
| before attempted audit | Stop worker and inspect command ownership. | Do not write attempted audit manually. |
| before legacy business commit | Stop worker and confirm no business mutation was committed. | Do not run business mutation by hand. |
| before success audit retry | Stop retry path and preserve post-commit degradation visibility. | Do not hide audit degradation. |
| before evidence preparation | Stop worker and inspect evidence preconditions. | Do not fabricate evidence. |
| after evidence prepared before finalize | Inspect local evidence, outbox, and audit phases. | Do not mark evidence confirmed. |
| before evidence-gated finalize | Treat as finalize recovery risk. | Do not expose stale success. |

## Finalize Recovery Required

`FINALIZE_RECOVERY_REQUIRED` means local evidence-gated finalize could not safely complete. The system must keep recovery
visible instead of reporting success from stale state.

Safe inspection:

1. Read command by command id or idempotency hash.
2. Verify model version, action, resource type, state, execution status, degradation reason, lease owner hash, and
   response snapshot presence.
3. Inspect outbox record and local audit phase ids.
4. Check external confirmation state only through documented evidence/export paths.
5. Record the operator decision and required approver.

Forbidden actions:

- Do not manually change command state to success.
- Do not manually edit alert, fraud-case, trust incident, or outbox documents.
- Do not set evidence-confirmed fields without authoritative evidence.
- Do not issue a new idempotency key for the same mutation unless an approved recovery procedure requires it.

## Local Audit-Chain Contention

Symptoms:

- local audit phase append retries increase
- finalize latency grows
- commands enter recovery-required because local success audit cannot be appended safely

Safe response:

1. Check Mongo health, lock contention, unique-index errors, and write concern failures.
2. Check `app.audit.local-phase-writer.max-append-attempts`, `backoff-ms`, and `max-total-wait-ms`.
3. Keep evidence-gated finalize flags disabled or roll them back if contention prevents safe local evidence append.
4. Do not disable the local `SUCCESS` audit writer while evidence-gated finalize is enabled.

Metrics are operational signals only. They are not compliance evidence by themselves.

## Escalation Clock

- T+5m: triage command state, lease status, public status, outbox, and audit phases.
- T+15m: assign incident lead if recovery state remains ambiguous.
- T+30m: rollback decision required if recovery backlog grows or API success ambiguity appears.
- T+60m: post-incident review required.

## Audit Record Template

```text
command_reference: command_id_or_idempotency_hash_only
operator_identity:
required_authority:
reason_code:
action_taken:
evidence_checked:
approver_identity:
timestamp:
raw_idempotency_key_included: false
raw_payload_included: false
customer_sensitive_data_included: false
```

## Post-Incident Validation

- Recovery backlog is stable or decreasing.
- No false success responses are exposed.
- `FINALIZE_RECOVERY_REQUIRED` remains visible when recovery is still needed.
- Outbox ambiguity is addressed.
- Audit degradation is resolved or intentionally open with owner.
- Logs and metrics do not include high-cardinality or sensitive labels.
