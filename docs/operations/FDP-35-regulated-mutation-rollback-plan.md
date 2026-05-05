# FDP-35 Regulated Mutation Rollback Plan

Rollback must be explicit, reviewed, and reversible. Operators must not manually edit regulated mutation command documents or business aggregates.

## Disable FDP-29 Evidence-gated Finalize Flags

- Set global FDP-29 evidence-gated finalize flag false.
- Set submit-decision FDP-29 flag false.
- Existing FDP-29 commands continue to replay with their stored `mutation_model_version`.
- Do not downgrade existing `EVIDENCE_GATED_FINALIZE_V1` commands to legacy semantics.

## Disable Or Neutralize Checkpoint Adoption

Checkpoint renewal can be disabled only in non-bank/local test modes or through explicit reviewed config. Production must not silently use disabled checkpoint renewal. Safe fallback is to stop or recover according to durable command state, not to treat renewal absence as business progress.

## Lease Renewal Budget Rollback

- Reducing renewal budgets affects active PROCESSING commands at the next renewal.
- Expected result may be durable recovery/no-progress alerts.
- Review long-running PROCESSING dashboards before and after rollback.

## Outbox And Evidence Confirmation

- Rollback does not change Kafka/outbox semantics.
- Rollback does not create external finality.
- `FINALIZED_EVIDENCE_PENDING_EXTERNAL` remains pending until evidence is confirmed by existing evidence confirmation flow.

## Operator Rollback Drill

Before rollback:
- Capture counts by command state and execution status.
- Capture recovery backlog and long-running PROCESSING panels.
- Capture outbox pending/unknown counts.

After rollback:
- Verify no new public statuses.
- Verify no false success responses.
- Verify existing model-versioned commands replay with their original model version.
- Verify recovery/no-progress alerts are understood and triaged.

## Validation

- Feature flag off keeps legacy command creation behavior.
- Existing FDP-29 command replay remains model-version aware.
- Disabled checkpoint renewal is not allowed as silent production wiring.

