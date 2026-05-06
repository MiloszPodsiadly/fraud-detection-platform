# FDP-35 E2E And Modeled Chaos Test Plan

FDP-35 is a production-readiness proof branch. It proves readiness evidence for the existing regulated mutation safety model and does not enable production or bank mode.

FDP-35 provides modeled restart/recovery proof in CI. It verifies durable post-crash command states, replay policy, recovery API behavior, and operator visibility. It does not claim real OS/JVM/container process-kill chaos unless an explicit real-chaos job is implemented and run.

True OS/JVM/container termination chaos remains future scope unless explicitly implemented.

## Test Tags

- `production-readiness`
- `e2e`
- `recovery-proof`
- `integration`

## Commands

Run the FDP-35 readiness and regression jobs locally:

```bash
mvn -B -pl alert-service -am -Dgroups=production-readiness,e2e,recovery-proof,integration test
mvn -B -pl alert-service -am -Dtest=EvidenceGatedFinalizeCoordinatorIntegrationTest,EvidenceGatedFinalizeCoordinatorTest,RegulatedMutationLeaseFencingIntegrationTest,RegulatedMutationLeaseRenewalIntegrationTest,RegulatedMutationCheckpointRenewalExecutionTest,RegulatedMutationStaleWorkerExecutorIntegrationTest,RegulatedMutationRecoveryControllerTest,RegulatedMutationRollbackReadinessTest test
```

Run full alert-service tests:

```bash
mvn -B -pl alert-service -am test
```

## Required Scenarios

- Legacy submit-decision happy path E2E.
- Evidence-gated submit-decision happy path with flags enabled only in test context.
- Recovery state beats stale snapshot.
- Checkpoint renewal is not progress.
- Long-running PROCESSING is observable.
- Modeled crash after claim before attempted audit.
- Modeled crash after attempted audit before business mutation.
- Modeled crash after business commit before success audit.
- Modeled crash during FDP-29 finalize before commit.
- Modeled crash after FDP-29 local commit before external confirmation.
- Modeled crash with recovery state and stale snapshot.
- Rollback keeps recovery commands visible.
- Inspection response remains safe and bounded.

## CI Output

CI output must be copied from the workflow run into the branch description after execution. This repository document intentionally does not include sample success output.

## Limitations

These tests use Docker/Testcontainers and durable Mongo state modeling. They do not terminate the alert-service JVM or container. True process/container termination chaos is future/optional scope and must run under an explicit `real-chaos`/`docker-chaos` profile before any real-chaos evidence claim is allowed.
