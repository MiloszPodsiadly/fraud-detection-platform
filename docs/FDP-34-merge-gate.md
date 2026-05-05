# FDP-34 Merge Gate

FDP-34 is merge-safe only as explicit safe checkpoint adoption of the FDP-33 bounded renewal primitive. It is not a generic heartbeat system, production enablement, bank enablement, external-finality claim, or distributed lock.

## Merge Requirements

- explicit checkpoint list exists
- no scheduler
- no infinite heartbeat loop
- no public heartbeat endpoint
- renewal failure stops execution
- no domain mutation after failed checkpoint
- no evidence finalize mutation after failed checkpoint
- no outbox or success audit continuation after failed checkpoint
- budget exceeded remains durable recovery
- FDP-32 stale-worker tests still pass
- FDP-33 renewal primitive tests still pass
- FDP-29 integration tests still pass
- metrics are low-cardinality
- architecture tests guard checkpoint boundaries
- docs say Renewal preserves ownership, not progress
- docs say No generic heartbeat system
- docs say No automatic infinite renewal loop
- docs say Checkpoint renewal failure stops execution

## Required Tests

- `RegulatedMutationSafeCheckpointPolicyTest`
- `RegulatedMutationCheckpointRenewalServiceTest`
- `RegulatedMutationCheckpointRenewalExecutionTest`
- `RegulatedMutationArchitectureTest`
- `RegulatedMutationLeaseRenewalIntegrationTest`
- `EvidenceGatedFinalizeCoordinatorIntegrationTest`
- existing FDP-32 stale worker tests
- existing FDP-33 renewal tests

## Required Commands

Focused FDP-34/FDP-33/FDP-32/FDP-29 regression:

```bash
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=RegulatedMutationSafeCheckpointPolicyTest,RegulatedMutationCheckpointRenewalServiceTest,RegulatedMutationCheckpointRenewalExecutionTest,RegulatedMutationArchitectureTest,RegulatedMutationLeaseRenewalIntegrationTest,EvidenceGatedFinalizeCoordinatorIntegrationTest,RegulatedMutationStaleWorkerExecutorIntegrationTest" test
```

Full alert-service regression:

```bash
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl alert-service -am test
```

## Production And Bank Gate

FDP-34 does not enable production or bank behavior by itself. Production or bank operation requires transaction-mode `REQUIRED`, lease duration budget review, renewal budget review, dashboards, alerts, runbook drill, canary or staging soak, rollback plan, and separate operational approval.

## Non-Goals

- no public heartbeat endpoint
- no public heartbeat API
- no scheduler
- no automatic heartbeat scheduler
- no automatic infinite renewal loop
- no new mutation type
- no public API status changes
- no Kafka or outbox semantic changes
- no external finality
- no distributed lock
- no distributed ACID
- no process-kill chaos proof
- no FDP-29 production enablement
