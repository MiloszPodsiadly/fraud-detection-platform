# FDP-35 Merge Gate

## Scope

FDP-35 is readiness/proof only. FDP-35 provides production-readiness evidence for the existing regulated mutation safety model.

FDP-35 provides modeled restart/recovery proof in CI. It verifies durable post-crash command states, replay policy, recovery API behavior, and operator visibility. It does not claim real OS/JVM/container process-kill chaos unless an explicit real-chaos job is implemented and run.

## Non-Goals

- no new mutation semantics
- no FDP-29 production enablement
- no scheduler/heartbeat expansion
- no Kafka/outbox semantic changes
- no external finality
- no distributed ACID
- no true OS/JVM/container termination-chaos evidence unless a `real-chaos`/`docker-chaos` job exists and passes

True OS/JVM/container termination chaos remains future scope unless explicitly implemented.

## Required CI

- `fdp35-production-readiness` green
- `regulated-mutation-regression` green
- no skipped Docker/Testcontainers readiness proof
- surefire reports uploaded for both jobs

| Invariant | Required CI job | Test class/method | Artifact |
| --- | --- | --- | --- |
| no false success after modeled restart | `fdp35-production-readiness` | `RegulatedMutationRestartRecoveryProofTest.crashAfterBusinessCommitBeforeSuccessAuditLegacyRequiresOnlyExplicitRecoveryOrAuditRetry` | `fdp35-production-readiness-test-reports` |
| recovery beats stale snapshot | `fdp35-production-readiness` | `RegulatedMutationProductionReadinessE2ETest.recoveryStateBeatsSnapshotE2E` | `fdp35-production-readiness-test-reports` |
| FINALIZE_RECOVERY_REQUIRED beats stale snapshot | `regulated-mutation-regression` | `EvidenceGatedFinalizeCoordinatorTest.shouldNotReplayStaleCommittedSnapshotWhenFinalizeRecoveryRequired` | `regulated-mutation-regression-test-reports` |
| checkpoint renewal is not progress | `fdp35-production-readiness` | `RegulatedMutationProductionReadinessE2ETest.checkpointRenewalIsNotProgressE2E` | `fdp35-production-readiness-test-reports` |
| long-running PROCESSING is observable | `fdp35-production-readiness` | `RegulatedMutationProductionReadinessE2ETest.longRunningProcessingIsObservableE2E` | `fdp35-production-readiness-test-reports` |
| rollback disables new risky behavior without hiding recovery | `regulated-mutation-regression` | `RegulatedMutationRollbackReadinessTest.rollbackKeepsRecoveryCommandsVisible` | `regulated-mutation-regression-test-reports` |
| Docker/Testcontainers proof is green | `fdp35-production-readiness` | `RegulatedMutationProductionReadinessE2ETest.legacySubmitDecisionHappyPathE2E` | `fdp35-production-readiness-test-reports` |
| FDP-29/FDP-32/FDP-33/FDP-34 regressions are green | `regulated-mutation-regression` | `EvidenceGatedFinalizeCoordinatorIntegrationTest.shouldFinalizeSubmitDecisionThroughRealMongoCoordinatorPath`; `RegulatedMutationLeaseFencingIntegrationTest.nonClaimedRecoveryTransitionCannotOverwriteCurrentOwnerAfterLeaseTakeover`; `RegulatedMutationLeaseRenewalIntegrationTest.concurrentRenewalAtLastAllowedSlotAllowsOnlyOneSuccess`; `RegulatedMutationStaleWorkerExecutorIntegrationTest.legacyCheckpointBudgetExceededStopsBeforeBusinessMutationThroughRealMongoExecutorPath` | `regulated-mutation-regression-test-reports` |

## Required Proof Matrix

Every FDP-35 invariant must map to an exact test class and method in `docs/testing/FDP-35-regression-proof-matrix.md`.

## Required Docs

- dashboard thresholds concrete
- rollback plan executable
- recovery drill includes filled sample output marked as sample
- modeled restart wording present
- placeholder output removed

## Required API Safety

- inspection response safe
- no raw idempotency key
- no raw request, intent, or payload hash
- no raw lease owner
- no raw exception text or path
- inspection endpoint remains authority-gated, audited fail-closed, and rate-limited

## Required Config Guarantee

- no production enablement flags changed by FDP-35
- all risky features disabled by default
- rollback disables new paths without disabling fencing or recovery visibility

## Final Allowed Claim

FDP-35 provides production-readiness evidence for the existing regulated mutation safety model.

FDP-35 must prove readiness, not claim enablement.

Modeled restart/recovery, controller recovery behavior, Docker/Testcontainers readiness, rollback, dashboard, alert, and operator drill evidence are covered.

## Forbidden Claim

FDP-35 enables production/bank mode.
