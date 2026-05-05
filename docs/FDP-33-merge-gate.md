# FDP-33 Merge Gate

FDP-33 is merge-safe only as bounded owner-fenced lease renewal for regulated mutation commands. It is not production enablement for FDP-29 and not an external-finality claim.

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
- operational runbook exists at `docs/runbooks/FDP-33-lease-renewal-runbook.md`
- FDP-32 stale-worker and fencing tests pass
- FDP-31 claim and replay tests pass
- FDP-29 integration tests pass
- public API statuses unchanged
- Kafka and outbox semantics unchanged
- transaction boundaries unchanged
- FDP-29 feature flags unchanged

## Production And Bank Enablement Gate

Production or bank operation requires:

- transaction-mode `REQUIRED`
- lease duration budget reviewed
- renewal budget configured
- dashboards for renewal, stale rejection, takeover, remaining budget, and transition latency
- alerts for stale owner, expired lease, budget exceeded, non-renewable state, and repeated takeover
- canary or staging soak
- operator drill performed
- rollback plan

## Required Verification

Run the focused FDP-33 pack:

```bash
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=RegulatedMutationLeaseRenewalPolicyTest,RegulatedMutationLeaseRenewalServiceTest,RegulatedMutationLeaseRenewalIntegrationTest,RegulatedMutationLeaseRenewalStartupGuardTest,AlertServiceMetricsTest,RegulatedMutationArchitectureTest" test
```

Run the full alert-service test suite:

```bash
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl alert-service -am test
```

Paste Docker/Testcontainers E2E Output Here before production or bank enablement. Merge verification may rely on Maven/Testcontainers; production enablement still requires separate operational soak.

## Non-Goals

- no distributed lock
- no distributed ACID
- no external finality
- no process-kill chaos proof
- no public heartbeat endpoint
- no new mutation types
- no automatic heartbeat scheduler
