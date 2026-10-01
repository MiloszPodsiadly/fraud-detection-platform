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

## Transactional Outbox Persisted Contract Migration

The active `transactional_outbox_records` contract stores request and approval intent separately. Request evidence uses
`resolution_request_reason`, `resolution_requested_by`, `resolution_requested_at`, and the
`resolution_evidence_*` fields. Approval evidence uses `resolution_approval_reason`, `resolution_approved_by`,
`resolution_approved_at`, and the `resolution_approval_evidence_*` fields. `resolution_control_mode`,
`resolution_proposed_outcome`, and `publication_confirmation_provenance` retain the control and publication provenance.

Before deploying this contract, inspect the active collection as raw BSON for every document where
`resolution_reason` exists, including `PUBLISHED`, `FAILED_TERMINAL`, and `RECOVERY_REQUIRED` records. The startup
preflight is read-only and blocks while any such document remains; it never migrates or deletes evidence. Export those
documents to the approved immutable archive, then migrate a record only when independently verified audit evidence can
populate the complete canonical request or approval fields. `resolution_pending` alone is not evidence of who supplied
the reason and must not be used to fabricate approval provenance. Records that cannot be migrated without inference
must remain in the historical archive and be removed from the active collection through the approved offline data
change procedure. Verify the archive and canonical records before removing the retired field, then rerun the preflight.

## Operator Matrix

| Condition | Symptom | Impact | Safe action | Endpoint or control | Authority | Evidence | Retry or rollback guidance | Escalation |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `REGULATED_MUTATION_RECOVERY_REQUIRED` | Trust level reason code or recovery backlog | Mutation needs reconciliation | Inspect command and run bounded recovery | `POST /api/v1/regulated-mutations/recover` | `regulated-mutation:recover` | command id or idempotency hash | No manual business rollback claim | engineering |
| `FINALIZE_RECOVERY_REQUIRED` | finalize recovery required count > 0 | Finalize outcome requires recovery | Inspect command and local evidence | inspection plus regulated recovery endpoints | ops admin | command snapshot and evidence ids | No finalized or externally confirmed claim | security |
| `PUBLISH_CONFIRMATION_UNKNOWN` | outbox unknown count > 0 | Delivery confirmation ambiguous | Inspect outbox and resolve with evidence | `/api/v1/outbox/.../resolve-confirmation` | ops admin | broker evidence | Manual resolution requires idempotency and immutable dual-control evidence; its `MANUAL_*_ATTESTED` provenance is not independent broker verification | platform |
| `OUTBOX_FAILED_TERMINAL` | terminal delivery count > 0 | Outbox delivery stopped | Repair cause and resolve | outbox recovery | ops admin | event id | Do not silently republish with a new key | platform |
| `OUTBOX_PROJECTION_MISMATCH` | projection mismatch count > 0 | Alert cache disagrees with outbox source | Run bounded recovery | `POST /api/v1/outbox/recovery/run` | ops admin | outbox record | Outbox record remains source of truth | engineering |
| `TRUST_INCIDENT_CRITICAL_OPEN` | critical incident open | Control-plane risk | Acknowledge or resolve with evidence | trust incident endpoints | ops admin | incident id | No workflow automation claim | security |
| `TRUST_INCIDENT_UNACKNOWLEDGED_CRITICAL` | unacknowledged critical count | Unowned risk | Acknowledge | `/api/v1/trust/incidents/{id}/ack` | ops admin | incident id | Read endpoints remain read-only | security |
| `TRUST_INCIDENT_REFRESH_PARTIAL` | refresh partial | Local/dev semantics attempted | Switch config to `ATOMIC` | config/startup | operator | config diff | Bank/prod must fail closed | engineering |
| `EVIDENCE_CONFIRMATION_PENDING_TOO_LONG` | pending evidence age grows | External evidence delayed | Inspect evidence/export state | evidence export | audit read/admin | export fingerprint | Bounded retry only | platform |
| `EVIDENCE_CONFIRMATION_RECOVERY_REQUIRED` | evidence confirmation recovery count | Evidence confirmation requires recovery | Inspect command and anchors | inspection/export | ops admin | anchor status | No externally confirmed claim | security |
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

## Runtime Configuration

Transactional outbox runtime tuning uses only `OUTBOX_LEASE_DURATION`, `OUTBOX_MAX_ATTEMPTS`,
`OUTBOX_STALE_THRESHOLD`, `OUTBOX_PUBLISHER_DELAY_MS`, and
`OUTBOX_RECOVERY_STALE_PROCESSING_THRESHOLD`, mapped to the canonical `app.outbox.*` namespace. Object-store audit
anchor startup validation is controlled by `AUDIT_EXTERNAL_ANCHORING_OBJECT_STORE_STARTUP_CHECK_ENABLED` and defaults
to enabled; disabling it does not disable the separate fail-closed publication policy.

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
