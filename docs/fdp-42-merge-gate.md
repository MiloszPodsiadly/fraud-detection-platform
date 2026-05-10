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
- CI required check `fdp42-fraud-case-management` runs the complete FDP-42 proof suite and uploads test reports.

## No-Go Conditions

- Analyst lifecycle mutation can happen without an audit append in the same transaction runner callback.
- GET/list/search mutates durable case state.
- Closed case can be modified without explicit reopen.
- Invalid transition is accepted by the policy.
- Controller contains lifecycle business logic.
- Repository contains lifecycle policy logic.
- API leaks raw internal errors.
- FDP-42 changes FDP-31 through FDP-40 regulated mutation, Kafka/outbox, or release-governance semantics.
- FDP-42 docs claim evidence-gated finalize, lease fencing, replay safety, exactly-once delivery, or external finality.
- Local lifecycle POSTs are documented or implemented as idempotent replay-safe commands.

## Current Verification

CI check name: `fdp42-fraud-case-management`.

Run:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=FraudCaseTransitionPolicyTest,Fdp42FraudCaseManagementServiceTest,FraudCaseControllerTest,FraudCaseManagementServiceTest,FraudCaseMutationInvariantTest,FraudCaseAuditServiceTest,Fdp42FraudCaseAuditAppendOnlyArchitectureTest,Fdp42FraudCaseDocumentationNoOverclaimTest" test
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=AlertSecurityConfigTest,AlertSecurityConfigJwtEnabledTest,DemoAuthSecurityConfigTest,AnalystRoleTest,FraudCaseSecurityIntegrationTest" test
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=FraudCaseTransactionIntegrationTest" test
```

Required gate checklist:

1. No read endpoint mutates durable case state.
2. Every analyst lifecycle mutation writes audit in the same local transaction.
3. Audit failure rollback is directly tested for create, assign, note, decision, transition, close, and reopen.
4. Security filters are enabled in FDP-42 security tests.
5. Audit endpoint requires `FRAUD_CASE_AUDIT_READ`.
6. Duplicate-submit behavior is documented and tested.
7. Manual create does not copy alert ids into `transactionIds`.
8. Event ingestion is documented separately from analyst lifecycle.
9. Search uses Mongo criteria, not in-memory `findAll` filtering.
10. Audit append-only architecture tests pass.
11. Docs no-overclaim tests pass.
12. Existing regulated mutation tests pass.
13. CI has required FDP-42 proof job.

FDP-42 is not a production enablement claim. It is a clean product-domain module with audited lifecycle mutations.
It is not a regulated mutation finality claim, not evidence-gated finalize, not lease-fenced replay safety, and not
external finality.
