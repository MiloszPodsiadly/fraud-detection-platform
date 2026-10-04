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

Before deploying this contract, inspect both active collections as raw BSON. Every transactional outbox record must
have a non-negative `projection_revision`; every `PUBLISHED` record must have independently established
`publication_confirmation_provenance`; and every Alert outbox projection must have `decisionOutboxEventId` and
`decisionOutboxProjectionRevision` consistent with its authoritative outbox record. The scan includes unfinished and
terminal records and rejects retired `resolution_reason`, incomplete pending intent, incomplete approval/evidence, an
orphan Alert projection, a projection newer than its source, and stale projection state without reconciliation
eligibility. Active `PROCESSING` and `PUBLISH_ATTEMPTED` records must carry `lease_owner`, `lease_expires_at`, and a
unique `lease_claim_token`; the owner identifies a coordinator instance while the token fences one claim generation.
A legitimate record that never entered manual resolution does not require approval metadata.

The startup preflight is read-only and records only that the persisted outbox contract passed validation. Publisher,
recovery, manual resolution, and new authoritative outbox writes remain closed until Spring Boot emits
`ApplicationReadyEvent` after every required startup runner succeeds. A context refresh or successful outbox preflight
alone never opens the mutation gate, and a failed preflight cannot be reopened by a later lifecycle event. The preflight
never assigns broker provenance, invents request IDs or operators, migrates data, or deletes evidence. It streams both
complete collection passes and retains only bounded diagnostic samples in application memory; the sample limit never
limits validation coverage. Both joins target the foreign collection's indexed `_id` (`resource_id -> alerts._id` and
`decisionOutboxEventId -> transactional_outbox_records._id`), so no additional join index is required. Mongo execution
and client-side inspection share the fail-closed budget configured by
`OUTBOX_PREFLIGHT_STARTUP_VALIDATION_BUDGET` (default `PT30S`). A timeout, interruption, cursor failure, or database
exception fails startup and never means that zero invalid records were found.
For projection recovery, `projection_reconcile_after` is the authoritative next-eligibility time. A mismatch without
that timestamp enters the bounded unscheduled queue and receives a schedule when claimed; a failed repair receives a
future retry time and cannot be reclaimed early through its mismatch marker. Each recovery run fairly merges due
scheduled work with unscheduled mismatches, up to the documented batch limit, so repeated failures do not hot-loop or
starve later eligible records.
Before an offline change, export the affected records and their Alert projections to the approved immutable archive,
record hashes and collection counts, verify the archive can be read independently, and take a rollback-capable database
snapshot. Populate canonical fields only from durable audit or broker evidence. A historical `PUBLISHED` record may use
`BROKER_ACKNOWLEDGED` only when independent durable broker acknowledgement proves it; otherwise archive it and remove it
from the active collections through the approved change procedure. After migration, verify event/resource identity,
revision ordering, status, provenance, pending intent and approval evidence against the archive, rerun the preflight,
and start publisher/recovery only after it passes. If verification differs, stop deployment and restore the snapshot;
do not partially roll forward or synthesize missing facts.

Use a restored production-size database snapshot for the offline dry-run. Inventory unsupported historical records by
category before changing them, preserve the original BSON and hashes in the approved archive, prepare and test database
rollback, and verify the standard `_id` indexes are ready on both collections. Run the persisted-contract preflight as
an explicit offline validation step with publisher and recovery disabled and the same validation budget as the target
deployment; do not start the full bank/prod application profile, whose startup guards correctly require both controls.
A successful preflight must report the canonical Alert/outbox relationship across the full inventory; any timeout or
exception is a failed dry-run. After the approved evidence-preserving migration or archive operation, repeat the
preflight and only then enable publisher and recovery in a controlled deployment. There is no automatic destructive
migration and no synthetic broker provenance.

For substantial performance verification, use a separately executed staging exercise, not normal CI. Generate or
restore representative canonical outbox and Alert counts plus a small known-invalid tail record beyond the diagnostic
sample size, run Mongo `explain("executionStats")` for both documented lookup shapes, and run the isolated startup once
cold and once warm. Record collection cardinalities, documents examined, index use, elapsed time, peak database memory
and disk spill, and the configured budget. The run passes only when the complete scan finds the tail record, canonical
data passes after its removal, and both runs finish with operational headroom inside the deployment budget.

## Fraud Alert Publication Lifecycle

`fraud_alert_outbox_records` is the durable source for publication to `fraud.alerts`. Alert creation and its
deterministic publication intent commit in the same MongoDB transaction; Kafka delivery remains at-least-once.
`PROCESSING` means the broker send has not started and an expired fenced lease may be reclaimed while attempts remain.
`PUBLISH_ATTEMPTED` means the send started; an exception, acknowledgement timeout, worker loss, or failure to persist
the acknowledgement becomes `PUBLISH_CONFIRMATION_UNKNOWN` and is never retried automatically. A pre-send record that
uses its bounded attempt budget becomes `FAILED_TERMINAL` and is escalated instead of entering an infinite claim loop.

The scheduled and direct publisher entrypoints both use `OUTBOX_PUBLISHER_ENABLED` and remain closed until the shared
outbox startup readiness gate opens. Manual reconciliation also requires `OUTBOX_RECOVERY_ENABLED`, authenticated
`outbox:resolve` authority, an idempotency key, a CAS-protected current record, and durable audit events. Resolve an
ambiguous event as `PUBLISHED` only with `BROKER_OFFSET` evidence. A retry requires `BROKER_NON_DELIVERY` evidence from
an authoritative broker administration check; absence from an incomplete topic scan is not evidence of non-delivery.
Each accepted resolution is appended to `fraud_alert_outbox_resolutions` with its evidence and original response
snapshot. Reusing the same idempotency key with the same request returns that snapshot; changed input is rejected.
The backlog endpoint is `GET /api/v1/outbox/fraud-alerts/recovery/backlog`, and its gauges contain counts and age only,
never event IDs, payloads, customer data, or exception text.

## Operator Matrix

| Condition | Symptom | Impact | Safe action | Endpoint or control | Authority | Evidence | Retry or rollback guidance | Escalation |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `REGULATED_MUTATION_RECOVERY_REQUIRED` | Trust level reason code or recovery backlog | Mutation needs reconciliation | Inspect command and run bounded recovery | `POST /api/v1/regulated-mutations/recover` | `regulated-mutation:recover` | command id or idempotency hash | No manual business rollback claim | engineering |
| `FINALIZE_RECOVERY_REQUIRED` | finalize recovery required count > 0 | Finalize outcome requires recovery | Inspect command and local evidence | inspection plus regulated recovery endpoints | ops admin | command snapshot and evidence ids | No finalized or externally confirmed claim | security |
| `PUBLISH_CONFIRMATION_UNKNOWN` | outbox unknown count > 0 | Delivery confirmation ambiguous | Inspect outbox and resolve with evidence | `/api/v1/outbox/.../resolve-confirmation` | ops admin | broker evidence | Manual resolution requires idempotency and immutable dual-control evidence; its `MANUAL_*_ATTESTED` provenance is not independent broker verification | platform |
| Fraud alert `PUBLISH_CONFIRMATION_UNKNOWN` | `fraud_alert_outbox_confirmation_unknown_count` > 0 | The `fraud.alerts` send may already have succeeded | Resolve as published from a durable broker offset, or retry only from verified broker non-delivery evidence | `POST /api/v1/outbox/fraud-alerts/{eventId}/resolve-confirmation` | `outbox:resolve` | `BROKER_OFFSET` or `BROKER_NON_DELIVERY` | Never infer non-delivery from absence in an incomplete Kafka scan | platform |
| Fraud alert `FAILED_TERMINAL` | `fraud_alert_outbox_failed_terminal_count` > 0 | No further automatic pre-send claim is allowed | Repair the cause, verify non-delivery, then use governed reconciliation | fraud alert outbox recovery | `outbox:resolve` | verified broker non-delivery evidence and audit trail | The bounded retry budget is not reset automatically | platform |
| `OUTBOX_FAILED_TERMINAL` | terminal delivery count > 0 | Outbox delivery stopped | Repair cause and resolve | outbox recovery | ops admin | event id | Do not silently republish with a new key | platform |
| `RETRY_BUDGET_EXHAUSTED_BEFORE_PUBLISH_ATTEMPT` | terminal outbox record after an expired pre-publish claim or exhausted retryable record | Delivery was not attempted within the bounded retry budget | Inspect the authoritative record and repair the pre-publish failure before approved recovery | outbox recovery | ops admin | event id, attempts, claim generation | Do not classify this as broker-confirmation ambiguity or reset attempts in place | platform |
| `OUTBOX_PROJECTION_MISMATCH` | projection mismatch count > 0 | Alert cache disagrees with outbox source | Run bounded recovery | `POST /api/v1/outbox/recovery/run` | ops admin | outbox record | Outbox record remains source of truth | engineering |
| `OUTBOX_PROJECTION_RECONCILIATION_PENDING` | reconciliation pending count > 0, possibly with mismatch count 0 | Authoritative projection work remains scheduled | Run bounded recovery and inspect repeated projection failures | `POST /api/v1/outbox/recovery/run` | ops admin | outbox record and projection revision | A missing mismatch marker does not prove synchronization | engineering |
| `OUTBOX_RECOVERY_REQUIRED` | outbox recovery-required count > 0 | Publication state requires operator recovery | Inspect authoritative outbox state and evidence | outbox recovery controls | ops admin | outbox record | Do not infer recovery from the Alert projection | platform |
| `OUTBOX_RESOLUTION_PENDING_APPROVAL` | pending outbox resolution count > 0 | Dual-control publication resolution is incomplete | Complete approval with a distinct authenticated operator | `/api/v1/outbox/.../resolve-confirmation` | ops admin | immutable request and approval evidence | Pending resolution keeps FDP-24 degraded | security |
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

Transactional and fraud-alert outbox runtime controls use `OUTBOX_PUBLISHER_ENABLED`, `OUTBOX_RECOVERY_ENABLED`,
`OUTBOX_LEASE_DURATION`, `OUTBOX_MAX_ATTEMPTS`,
`OUTBOX_STALE_THRESHOLD`, `OUTBOX_PUBLISHER_DELAY_MS`, and
`OUTBOX_RECOVERY_STALE_PROCESSING_THRESHOLD`; startup scan time is bounded by
`OUTBOX_PREFLIGHT_STARTUP_VALIDATION_BUDGET`. These map to the canonical `app.outbox.*` namespace. Object-store audit
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
