# FDP-29 Finalize Recovery Required Runbook

This runbook applies to the disabled-by-default FDP-29 submit-decision local evidence-precondition-gated finalize path.

## Symptom

- command state: `FINALIZE_RECOVERY_REQUIRED`
- degradation reason: `FINALIZING_RETRY_REQUIRES_RECONCILIATION`

## Meaning

The coordinator found an expired or retried `FINALIZING` command and refused to rerun the business mutation blindly. This is fail-closed behavior. It prevents duplicate analyst decisions when the local transaction outcome is not proven from command state alone.

## Impact

Visible business state may be unchanged or ambiguous until inspected. Clients must continue using the same idempotency key and must not submit a replacement mutation for the same decision.

## Required Authority

Only operators with regulated mutation recovery/admin authority may perform recovery inspection or repair.

## Safe Inspection Steps

1. Inspect the regulated mutation command by command id or idempotency hash.
2. Inspect the alert aggregate and compare actor, resource, action, and decision intent.
3. Inspect the authoritative `transactional_outbox_records` row by mutation command id.
4. Inspect the command response snapshot.
5. Inspect the local audit phase record for deterministic request id `<command_id>:SUCCESS`.

## Evidence Confirmation Batch Limit

`confirmPendingEvidence(limit)` processes at most `100` commands per call. `limit <= 0` is an explicit no-op and returns `0`; it must not be used as an implicit minimum-one recovery run.

## Deterministic Outcomes

- If no business mutation, outbox record, response snapshot, local success audit, or local finalize marker exists: keep the command non-success and retry only through an explicit recovery policy.
- If the business mutation, outbox record, response snapshot, local success audit, and local finalize marker all match canonical intent: repair to `FINALIZED_EVIDENCE_PENDING_EXTERNAL`.
- If any inspected state is inconsistent: keep `FINALIZE_RECOVERY_REQUIRED` and open a trust incident.

## Forbidden Actions

- Do not submit a new idempotency key for the same mutation.
- Do not manually edit `AlertDocument`.
- Do not mark evidence confirmed without authoritative outbox evidence and required external/signature evidence proof.
- Do not treat `FINALIZE_RECOVERY_REQUIRED` as a committed success response.

## Signals

Monitor:

- `evidence_gated_finalize_recovery_required_total{reason="FINALIZING_RETRY_REQUIRES_RECONCILIATION"}`
- regulated mutation inspection state and degradation reason
- trust incident backlog if inconsistent state is found

Metric labels must remain bounded. They must not include command ids, alert ids, actors, idempotency keys, audit ids, outbox ids, raw exception messages, or paths.
