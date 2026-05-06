# FDP-36 Final Proof Pack

## Scope

FDP-36 is proof and operations scope only. It does not change runtime regulated mutation semantics, does not change public statuses, does not change transaction boundaries, and does not enable production behavior.

The real-chaos proof kills the actual alert-service JVM/process running regulated mutation execution, recovery, and inspection endpoints.

Docker/Testcontainers are infrastructure dependencies, not the killed alert-service image.

FDP-36 kills and restarts the real alert-service JVM/process. Most crash windows are durable-state crash-window proofs, not live in-flight instruction-boundary kills.

## Invariants

| Invariant | Test class | Method | CI job | Proof level |
| --- | --- | --- | --- | --- |
| no false committed success | `RegulatedMutationRealAlertServiceChaosIT` | `shouldNotReturnFalseSuccessAfterKillDuringLegacyBusinessCommitting` | `fdp36-real-chaos` | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| no false finalized confirmed | `RegulatedMutationRealAlertServiceChaosIT` | `shouldNotFinalizeSuccessAfterKillInFdp29FinalizingWithoutProof` | `fdp36-real-chaos` | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| recovery beats stale snapshot | `RegulatedMutationPostRestartApiBehaviorTest` | `recoveryRequiredWithStaleSnapshot_afterRestartReturnsRecoveryNotSuccess` | `regulated-mutation-regression` | `API_PERSISTED_STATE_PROOF` |
| no duplicate business mutation | `RegulatedMutationRealAlertServiceChaosIT` | `shouldRetrySuccessAuditOnlyAfterKillInSuccessAuditPendingWithoutSecondBusinessMutation` | `fdp36-real-chaos` | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| no duplicate outbox | `RegulatedMutationRealAlertServiceEvidenceIntegrityIT` | `replayAfterRestartMustNotCreateSecondOutboxRecord` | `fdp36-real-chaos` | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| no duplicate SUCCESS audit | `RegulatedMutationRealAlertServiceEvidenceIntegrityIT` | `replayAfterRestartMustNotCreateSecondSuccessAudit` | `fdp36-real-chaos` | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| no duplicate local audit anchor | `RegulatedMutationRealAlertServiceEvidenceIntegrityIT` | `replayAfterRestartMustNotCreateSecondLocalAuditAnchorForSameCommandPhase` | `fdp36-real-chaos` | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| pending external remains pending | `RegulatedMutationRealAlertServiceChaosIT` | `shouldRemainPendingExternalAfterKillWhenFdp29LocalCommitCompletedButExternalEvidencePending` | `fdp36-real-chaos` | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF` |
| one live in-flight checkpoint does not falsely commit | `RegulatedMutationLiveInFlightKillIT` | `liveInFlightBeforeBusinessMutationKillDoesNotCommitOrPublish` | `fdp36-real-chaos` | `REAL_ALERT_SERVICE_KILL`, `REAL_ALERT_SERVICE_RESTART_API_PROOF`, `LIVE_IN_FLIGHT_REQUEST_KILL` |
| checkpoint/renewal is not progress | `RegulatedMutationSafeCheckpointPolicyTest` | checkpoint policy tests | `regulated-mutation-regression` | `MODELED_DURABLE_STATE_PROOF` |
| long-running processing observable | `RegulatedMutationPostRestartApiBehaviorTest` | `processingExpiredAfterRestartReturnsObservableStateNotSuccess` | `regulated-mutation-regression` | `API_PERSISTED_STATE_PROOF` |

## Unsupported / Future Scope

- real OS signal taxonomy beyond forced JVM/process kill
- host pause and scheduler freeze
- network partition between alert-service and Mongo/Kafka/Redis
- Mongo primary failover during the mutation transaction
- Kafka broker kill during outbox publication
- external evidence authority outage confirmation semantics
- full production Docker image container kill remains future scope

Filled by CI artifact: `fdp36-proof-summary.md`.

Example only, not production evidence.

## Enablement Decision

The maximum FDP-36 decision is `READY_FOR_ENABLEMENT_REVIEW`. This is not production enablement.

Required approvers before any later enablement: platform owner, security owner, operations owner, and bank-mode release owner.

Rollback preconditions: FDP-29 production mode remains default-off, existing rollback flags remain available, and operator runbooks identify recovery-required commands before rollback.

## Raw Sensitive Data Rule

FDP-36 evidence must not expose raw idempotency key, raw request hash, raw lease owner, raw resource ID when classified sensitive, raw stack trace, tokens, paths, or correlation-secret material.
