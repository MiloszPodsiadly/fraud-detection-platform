# FDP-42 Merge Gate

Status: branch merge gate.

## Required Checks

- Fraud case lifecycle policy tests pass.
- Fraud case service tests pass for create, assignment, note conflict, and audit append behavior.
- Fraud case controller tests pass for create, search, validation, and conflict responses.
- Fraud case security tests pass with filters enabled for `/api/v1/fraud-cases/**` and `/api/fraud-cases/**`.
- Fraud case Mongo transaction integration tests prove case state and audit append commit or roll back together.
- Fraud case documentation no-overclaim tests pass for local lifecycle scope.
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
- FDP-42 docs claim evidence-gated finalize, lease fencing, replay safety, exactly-once delivery, or external finality.
- Local lifecycle POSTs are documented or implemented as idempotent replay-safe commands.

## Current Verification

Run:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=FraudCaseTransitionPolicyTest,Fdp42FraudCaseManagementServiceTest,FraudCaseControllerTest,FraudCaseManagementServiceTest,FraudCaseMutationInvariantTest,FraudCaseAuditServiceTest,Fdp42FraudCaseAuditAppendOnlyArchitectureTest,Fdp42FraudCaseDocumentationNoOverclaimTest" test
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=AlertSecurityConfigTest,AlertSecurityConfigJwtEnabledTest,DemoAuthSecurityConfigTest,AnalystRoleTest,FraudCaseSecurityIntegrationTest" test
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=FraudCaseTransactionIntegrationTest" test
```

FDP-42 is not a production enablement claim. It is a clean product-domain module with audited lifecycle mutations.
It is not a regulated mutation finality claim, not evidence-gated finalize, not lease-fenced replay safety, and not
external finality.
