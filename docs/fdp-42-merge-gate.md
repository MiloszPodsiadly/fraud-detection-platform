# FDP-42 Merge Gate

Status: branch merge gate.

## Required Checks

- Fraud case lifecycle policy tests pass.
- Fraud case service tests pass for create, assignment, note conflict, and audit append behavior.
- Fraud case controller tests pass for create, search, validation, and conflict responses.
- Existing fraud-case regulated mutation regression tests pass.
- Alert-service security configuration tests pass after endpoint additions.

## No-Go Conditions

- Case mutation can happen without an audit append in the same transaction runner callback.
- Closed case can be modified without explicit reopen.
- Invalid transition is accepted by the policy.
- Controller contains lifecycle business logic.
- Repository contains lifecycle policy logic.
- API leaks raw internal errors.
- FDP-42 changes FDP-31 through FDP-40 regulated mutation, Kafka/outbox, or release-governance semantics.

## Current Verification

Run:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=FraudCaseTransitionPolicyTest,Fdp42FraudCaseManagementServiceTest,FraudCaseControllerTest,FraudCaseManagementServiceTest,FraudCaseMutationInvariantTest" test
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=AlertSecurityConfigTest,AlertSecurityConfigJwtEnabledTest,DemoAuthSecurityConfigTest,AnalystRoleTest,FraudCaseControllerTest" test
```

FDP-42 is not a production enablement claim. It is a clean product-domain module with audited lifecycle mutations.
