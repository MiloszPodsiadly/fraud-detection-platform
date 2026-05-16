# Alert Service Operations Runbook

Status: current operator runbook.

## Purpose

This runbook covers alert-service operational conditions that require operator action, reconciliation, or escalation.
Each action must be performed by an operator with the documented backend authority. UI visibility is not an
authorization boundary.

## Scope

In scope:

- regulated mutation recovery visibility
- outbox ambiguity and projection mismatch
- audit degradation and sensitive-read audit unavailability
- trust incident acknowledgement and resolution
- transaction capability startup failure
- local trust authority or external anchor degradation

Out of scope:

- manual business aggregate edits
- bypassing security, audit, or regulated mutation startup guards
- declaring production enablement, bank certification, external finality, or legal evidence

## Operator Matrix

| Condition | Symptom | Impact | Safe action | Endpoint or control | Authority | Evidence | Retry or rollback guidance | Escalation |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `REGULATED_MUTATION_RECOVERY_REQUIRED` | Trust level reason code or recovery backlog | Mutation needs reconciliation | Inspect command and run bounded recovery | `POST /api/v1/regulated-mutations/recover` | `regulated-mutation:recover` | command id or idempotency hash | No manual business rollback claim | engineering |
| `COMMITTED_DEGRADED` | committed degraded count > 0 | Post-commit audit degradation | Inspect command and degradation | inspection plus audit degradation endpoints | ops admin | command snapshot and audit ids | No `FULLY_ANCHORED` claim | security |
| `PUBLISH_CONFIRMATION_UNKNOWN` | outbox unknown count > 0 | Delivery confirmation ambiguous | Inspect outbox and resolve with evidence | `/api/v1/outbox/.../resolve-confirmation` | ops admin | broker evidence | Manual resolution requires idempotency and evidence | platform |
| `OUTBOX_FAILED_TERMINAL` | terminal delivery count > 0 | Outbox delivery stopped | Repair cause and resolve | outbox recovery | ops admin | event id | Do not silently republish with a new key | platform |
| `OUTBOX_PROJECTION_MISMATCH` | projection mismatch count > 0 | Alert cache disagrees with outbox source | Run bounded recovery | `POST /api/v1/outbox/recovery/run` | ops admin | outbox record | Outbox record remains source of truth | engineering |
| `TRUST_INCIDENT_CRITICAL_OPEN` | critical incident open | Control-plane risk | Acknowledge or resolve with evidence | trust incident endpoints | ops admin | incident id | No workflow automation claim | security |
| `TRUST_INCIDENT_UNACKNOWLEDGED_CRITICAL` | unacknowledged critical count | Unowned risk | Acknowledge | `/api/v1/trust/incidents/{id}/ack` | ops admin | incident id | Read endpoints remain read-only | security |
| `TRUST_INCIDENT_REFRESH_PARTIAL` | refresh partial | Local/dev semantics attempted | Switch config to `ATOMIC` | config/startup | operator | config diff | Bank/prod must fail closed | engineering |
| `EVIDENCE_CONFIRMATION_PENDING_TOO_LONG` | pending evidence age grows | External evidence delayed | Inspect evidence/export state | evidence export | audit read/admin | export fingerprint | Bounded retry only | platform |
| `EVIDENCE_CONFIRMATION_FAILED` | failed evidence count | Evidence incomplete | Inspect command and anchors | inspection/export | ops admin | anchor status | No `FULLY_ANCHORED` claim | security |
| `AUDIT_DEGRADATION_UNRESOLVED` | unresolved degradation | Audit trust degraded | Resolve with verified evidence | audit degradation endpoint | ops admin | evidence reference | No hidden repair | security |
| `TRANSACTION_CAPABILITY_FAILURE` | startup fails | Bank/prod stays closed | Fix Mongo transaction capability | startup | operator | startup logs without secrets | No fail-open | database |
| `SENSITIVE_READ_AUDIT_UNAVAILABLE` | sensitive read returns `503` | Operational reads blocked | Restore audit persistence | affected GET endpoint | audit read/admin | stable error code/message | Do not disable audit | database |
| `EXTERNAL_ANCHOR_GAP` | coverage degraded | External evidence lag | Run publisher or reconcile | coverage/export | ops admin | missing ranges | No best-effort head claim | platform |
| `TRUST_AUTHORITY_UNAVAILABLE` | trust authority unavailable | Signature/attestation degraded | Restore authority | trust authority health | operator | signed status | No local signer production claim | security |

## Safe Operator Actions

1. Identify the affected command, outbox event, incident, or endpoint family using an approved operational lookup.
2. Prefer command id, event id, audit id, incident id, or idempotency hash over raw request identifiers.
3. Confirm current runtime state before taking action.
4. Record operator identity, authority, reason code, evidence checked, timestamp, and approver when dual control applies.
5. Use bounded recovery endpoints or documented resolution endpoints only.
6. Re-check metrics and audit records after recovery.

## Forbidden Actions

- Do not manually edit business aggregates.
- Do not paste raw idempotency keys, cursor values, tokens, raw query strings, customer identifiers, card/account values,
  stack traces, or exception messages into tickets, dashboards, examples, or audit metadata.
- Do not disable audit to restore reads.
- Do not rewrite lease owners or command states.
- Do not resolve broker confirmation without broker evidence.
- Do not claim production enablement, bank certification, legal evidence, WORM storage, distributed ACID, or exactly-once
  Kafka delivery.

## Escalation

Escalate to the fraud platform incident lead when recovery state remains ambiguous after 15 minutes, when any
operator action needs manual state repair, or when evidence needed for safe resolution is missing.

Escalate to security when audit degradation, trust authority failure, external anchor gaps, or sensitive-read audit
failures are involved.

## Example Evidence Note

```text
condition: REGULATED_MUTATION_RECOVERY_REQUIRED
command_reference: command_id_or_idempotency_hash_only
operator_identity: fraud_ops_admin
authority: regulated-mutation:recover
evidence_checked: command_state, outbox_record, audit_phase_ids, current_trust_level
action_taken: bounded recovery inspection and recovery endpoint invocation
forbidden_actions_confirmed_not_taken: true
timestamp: 2026-05-16T00:00:00Z
```
