# FDP-33 Merge Gate

Status: branch evidence.


FDP-33 is merge-safe only as primitive + readiness: bounded owner-fenced lease renewal for regulated mutation commands, durable budget-exceeded recovery, operational runbook, and low-cardinality metrics. It is not production enablement for FDP-29 and not an external-finality claim.

## Merge-Safe Scope

- bounded owner-fenced renewal primitive
- durable budget-exceeded recovery
- operational runbook
- low-cardinality metrics
- no public heartbeat endpoint
- no automatic scheduler
- no transaction boundary change

## Merge Requirements

- owner-fenced renewal implemented
- stale owner cannot renew
- expired owner cannot renew
- terminal and recovery states cannot renew
- invalid extension and missing command have precise bounded rejection reasons
- unknown or mismatched model version fails closed
- direct renewal budget exhaustion marks durable recovery-required state
- max renewal count enforced
- max total lease duration enforced
- concurrent renewal at the final count slot allows only one success without business/evidence field changes
- renewal cannot update business or evidence fields
- renewal cannot create infinite `PROCESSING`
- bank/prod startup guard rejects unsafe renewal budget configuration
- metrics use low-cardinality labels only
- operational runbook exists at `docs/runbooks/regulated_mutation_recovery.md`
- observability dashboard contract exists at `docs/observability/fdp_33_lease_renewal_dashboard.md`
- FDP-32 stale-worker and fencing tests pass
- FDP-31 claim and replay tests pass
- FDP-29 integration tests pass
- public API statuses unchanged
- Kafka and outbox semantics unchanged
- transaction boundaries unchanged
- FDP-29 feature flags unchanged

## Production And Bank Enablement Gate

Production or bank operation requires:

- separate production/bank gate
- transaction-mode `REQUIRED`
- lease duration budget reviewed
- renewal budget configured
- dashboards for renewal, stale rejection, takeover, remaining budget, and transition latency
- alerts for stale owner, expired lease, budget exceeded, non-renewable state, and repeated takeover
- canary or staging soak
- operator drill performed
- safe checkpoint review if renewal is wired into executors
- rollback plan

## Required Verification

Required tests before merge:

- `RegulatedMutationLeaseRenewalPolicyTest`
- `RegulatedMutationLeaseRenewalServiceTest`
- `RegulatedMutationLeaseRenewalIntegrationTest`
- `RegulatedMutationLeaseRenewalStartupGuardTest`
- `AlertServiceMetricsTest`
- `RegulatedMutationArchitectureTest`
- FDP-32 stale-worker fencing tests
- FDP-29 integration tests

Required CI command:

```bash
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=RegulatedMutationLeaseRenewalPolicyTest,RegulatedMutationLeaseRenewalServiceTest,RegulatedMutationLeaseRenewalIntegrationTest,RegulatedMutationLeaseRenewalStartupGuardTest,AlertServiceMetricsTest,RegulatedMutationArchitectureTest" test
```

Run the full alert-service test suite:

```bash
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl alert-service -am test
```

## Non-Goals

- no distributed lock
- no distributed ACID
- no external finality
- no process-kill chaos proof
- no public heartbeat endpoint
- no public heartbeat API
- no new mutation types
- no automatic heartbeat scheduler
- no FDP-29 production enablement
