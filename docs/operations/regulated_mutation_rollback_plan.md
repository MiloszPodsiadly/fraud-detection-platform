# Regulated Mutation Rollback Plan

Status: current rollback review template.

## Scope

Rollback must be explicit, reviewed, tested, and reversible. Operators must not manually edit regulated mutation
command documents or business aggregates.

CI provides modeled restart/recovery proof. It verifies durable post-crash command states, replay policy,
recovery API behavior, and operator visibility. It does not claim real OS/JVM/container process-kill chaos unless an
explicit real-chaos job is implemented and run.

True OS/JVM/container termination chaos remains future scope unless explicitly implemented.

## Executable Rollback Checklist

| Field | Value |
| --- | --- |
| owner | Fraud Platform On-call |
| approver | Fraud Platform Incident Lead |
| timestamp | fill at execution time |
| config before | capture feature flags, renewal budget, transaction mode |
| config after | capture changed values |
| CI job link | `fdp35-production-readiness` and `regulated-mutation-regression` |
| recovery backlog before/after | capture totals and by-state counts |
| API smoke result | recovery response, no false success |
| rollback validation result | `RegulatedMutationRollbackReadinessTest` green |
| post-rollback monitoring window | minimum 60 minutes |
| explicit no new success claims observed | required yes/no |
| post-rollback owner signoff | required owner name and timestamp |

## Preserve The Current Runtime Model

- Roll back only to a build that supports `EVIDENCE_GATED_FINALIZE_V1` and the current persisted contract.
- Run the read-only persisted-model preflight before restart.
- Do not add a runtime selector or reinterpret missing, retired, or unknown versions.
- Do not downgrade current commands to removed semantics.
- Dual control is required for deployment rollback.

## Disable Or Neutralize Checkpoint Adoption

Checkpoint renewal can be disabled only in non-bank/local test modes or through explicit reviewed config. Production
must not silently use disabled checkpoint renewal. Safe fallback is to stop or recover according to durable command
state, not to treat renewal absence as business progress.

## Lease Renewal Budget Rollback

- Reducing renewal budgets affects active PROCESSING commands at the next renewal.
- Expected result may be durable recovery/no-progress alerts.
- Review long-running PROCESSING dashboards before and after rollback.
- Do not increase budget blindly.

## Outbox And Evidence Confirmation

- Rollback does not change Kafka/outbox semantics.
- Rollback does not create external finality.
- `FINALIZED_EVIDENCE_PENDING_EXTERNAL` remains pending until evidence is confirmed by existing evidence confirmation flow.

## Operator Rollback Drill

Before rollback:

- Capture counts by command state and execution status.
- Capture recovery backlog and long-running PROCESSING panels.
- Capture outbox pending/unknown counts.
- Capture dashboard threshold state.

After rollback:

- Verify no new public statuses.
- Verify no false success responses.
- Verify existing model-versioned commands replay with their original model version.
- Verify recovery/no-progress alerts are understood and triaged.
- Verify no `FINALIZE_RECOVERY_REQUIRED` command is hidden.
- Record post-rollback owner signoff with timestamp after the monitoring window.

## Validation

- `RegulatedMutationRollbackReadinessTest.disablingCheckpointRenewal_doesNotDisableFencing`
- `RegulatedMutationRollbackReadinessTest.shrinkingRenewalBudget_doesNotCreateFalseSuccess`
- `RegulatedMutationRollbackReadinessTest.rollbackKeepsRecoveryCommandsVisible`
- `RegulatedMutationRollbackReadinessTest.currentRuntimeDoesNotStartAutonomousMutationSchedulers`
- `RegulatedMutationRollbackReadinessTest.rollbackApiSmoke_doesNotHideRecovery`

## Filled Sample Output

Sample from local/Testcontainers drill, not production evidence.

```text
rollback_id: FDP35-ROLLBACK-2026-05-05-001
owner: fraud-platform-on-call
approver: fraud-platform-incident-lead
timestamp: 2026-05-05T18:30:00Z
config_before: transaction_mode=REQUIRED, checkpoint_renewal_enabled=true
config_after: transaction_mode=REQUIRED, checkpoint_renewal_enabled=false
ci_job_link: local RegulatedMutationRollbackReadinessTest
recovery_backlog_before: total=2, FINALIZE_RECOVERY_REQUIRED=1
recovery_backlog_after: total=2, FINALIZE_RECOVERY_REQUIRED=1
api_smoke_result: RECOVERY_REQUIRED response explicit, no committed success
rollback_validation_result: PASS
post_rollback_monitoring_window: 60m
no_new_success_claims_observed: true
post_rollback_owner_signoff: fraud-platform-on-call at 2026-05-05T19:30:00Z
```
