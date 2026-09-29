# Regulated Mutation Checkpoint Adoption

Status: current runtime contract.


Checkpoint renewal must not be treated as business progress.
do not increase budget blindly. do not bypass checkpoint renewal.
do not bypass fencing. do not increase lease budget blindly.

Checkpoint adoption uses the bounded renewal primitive explicitly. Renewal preserves bounded ownership, not progress.
It is not a generic heartbeat system, production enablement, bank enablement, external-finality claim, or distributed
lock.

## Merge Requirements

- explicit checkpoint list exists
- no scheduler
- no infinite heartbeat loop
- no public heartbeat endpoint
- renewal failure stops execution
- no domain mutation after failed checkpoint
- no evidence finalize mutation after failed checkpoint
- no outbox or success audit continuation after failed checkpoint
- checkpoint renewal failure is not classified as post-commit audit degradation
- successful renewal is not treated as ATTEMPTED audit completed, business mutation completed, outbox written, success audit recorded, evidence prepared, local finalize completed, external confirmation completed, Kafka delivered, or legal/auditor finality reached
- checkpoint failure stops execution immediately with no further mutation/outbox/audit/snapshot/transition
- budget exceeded remains durable recovery
- checkpoint-renewal extension is positive and within the configured renewal budget
- production executors require the Spring-managed checkpoint renewal service
- stale-worker tests still pass
- renewal primitive tests still pass
- current evidence-gated finalize integration tests still pass
- real Mongo executor-path checkpoint tests cover current-model success, stale/expired, and budget paths
- metrics are low-cardinality
- `regulated_mutation_checkpoint_no_progress_total` is not emitted for successful renewal
- architecture tests guard checkpoint boundaries
- docs say Renewal preserves ownership, not progress
- docs say No generic heartbeat system
- docs say No automatic infinite renewal loop
- docs say Checkpoint renewal failure stops execution

## Required Tests

- `RegulatedMutationSafeCheckpointPolicyTest`
- `RegulatedMutationCheckpointRenewalServiceTest`
- `RegulatedMutationCheckpointRenewalExecutionTest`
- real Mongo checkpoint executor-path coverage in `RegulatedMutationStaleWorkerExecutorIntegrationTest`
- `RegulatedMutationArchitectureTest`
- `RegulatedMutationLeaseRenewalIntegrationTest`
- `EvidenceGatedFinalizeCoordinatorIntegrationTest`
- existing stale worker tests
- existing renewal tests

## Required Commands

Focused checkpoint, renewal, fencing, and finalize regression:

```bash
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=RegulatedMutationSafeCheckpointPolicyTest,RegulatedMutationCheckpointRenewalServiceTest,RegulatedMutationCheckpointRenewalExecutionTest,RegulatedMutationArchitectureTest,RegulatedMutationLeaseRenewalIntegrationTest,EvidenceGatedFinalizeCoordinatorIntegrationTest,RegulatedMutationStaleWorkerExecutorIntegrationTest" test
```

Full alert-service regression:

```bash
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl alert-service -am test
```

## Production And Bank Gate

Checkpoint adoption does not enable production or bank behavior by itself. Production or bank operation requires
transaction-mode `REQUIRED` for bank-grade stale-worker business-write safety, positive checkpoint-renewal extension
within `max-single-extension` and `max-total-lease-duration`, lease duration and renewal budget review, dashboards and
alerts, an operator drill, canary or staging soak, rollback planning, and separate operational approval.

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
- no alternate regulated mutation runtime or fallback
