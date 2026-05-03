# FDP-29 Migration and Rollout Strategy

FDP-29 is a design contract plus a feature-flagged submit-decision implementation prototype. It introduces evidence-gated finalize without breaking FDP-25/FDP-26/FDP-27/FDP-28 behavior when the default-disabled flags remain off.

## Feature Flag

Flags:

```properties
app.regulated-mutations.evidence-gated-finalize.enabled=false
app.regulated-mutations.evidence-gated-finalize.submit-decision.enabled=false
app.regulated-mutations.transaction-mode=REQUIRED
```

Both evidence-gated flags must be enabled for new submit-decision commands to use `EVIDENCE_GATED_FINALIZE_V1`. `transaction-mode=REQUIRED`, transaction capability probe, transactional outbox repository, outbox recovery, and submit-decision recovery strategy are required at startup. If these are missing, startup fails closed instead of silently falling back.

## Per-Mutation Rollout

| Mutation Path | Rollout Approach | Initial Compatibility |
| --- | --- | --- |
| Submit analyst decision | Implemented prototype behind disabled-by-default flags because it already uses regulated command, local transaction, outbox, and response snapshots. | Existing `COMMITTED_EVIDENCE_PENDING` remains valid for legacy commands. |
| Fraud-case update | Enable after submit-decision semantics prove stable. | Existing response snapshots and recovery strategies continue. |
| Trust incident ACK/RESOLVE/REFRESH | Enable after trust incident dedupe and audit semantics are mapped. | Current atomic/partial refresh restrictions remain. |
| Outbox confirmation resolution | Treat as operational regulated mutation; do not conflate with broker delivery success. | `PUBLISH_CONFIRMATION_UNKNOWN` remains accepted. |

## Legacy Command Replay

Legacy commands should be replayed using their stored model/version. A future field such as `mutation_model_version` may distinguish:

- `legacy-regulated-mutation`
- `EVIDENCE_GATED_FINALIZE_V1`

If no version exists, treat the command as legacy.

## Compatibility Mode

`COMMITTED_EVIDENCE_PENDING` remains valid until migration is complete. It maps to `FINALIZED_EVIDENCE_PENDING_EXTERNAL` for read/reporting purposes only when local commit and evidence policy can be verified.

Legacy degraded states must remain degraded. Do not promote `COMMITTED_DEGRADED`, `COMMITTED_EVIDENCE_INCOMPLETE`, or `LOCAL_STATUS_UNVERIFIED` to confirmed states without reconciliation.

## Dual-Read Strategy

APIs should determine the response model from command metadata:

1. If command has evidence-gated model version, return FDP-29 response statuses.
2. If command has legacy model version or no model version, return current compatibility statuses.
3. If a business aggregate exists without command proof, report recovery/inspection state rather than confirmed finalize.

## Rollback Plan

To disable the model safely:

1. Set `app.regulated-mutations.evidence-gated-finalize.enabled=false`.
2. Stop creating new evidence-gated commands.
3. Continue replaying existing evidence-gated commands with their model version.
4. Do not downgrade in-flight `FINALIZING` commands to legacy committed states.
5. Keep recovery tooling enabled for both models.

## Observability During Migration

Future implementation should add only bounded, low-cardinality signals such as:

- command model version
- finalize state
- evidence gate rejection reason
- recovery-required reason
- external evidence pending/confirmed counts

Metric labels must not include actor ids, resource ids, idempotency keys, tokens, exception messages, or raw paths.

## Trust-Level Fields

System trust should distinguish:

- legacy committed evidence pending
- evidence-gated pending finalize
- finalized visible with external evidence pending
- finalize recovery required
- evidence confirmed

No migration state may be reported as healthy if required evidence is unavailable, unverified, or ambiguous.
