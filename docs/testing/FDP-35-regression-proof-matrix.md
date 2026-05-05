# FDP-35 Regression Proof Matrix

| Invariant | Source FDP | Test class | Test method | Expected result | Remaining limitation |
| --- | --- | --- | --- | --- | --- |
| Local evidence-precondition-gated finalize | FDP-29 | `EvidenceGatedFinalizeCoordinatorIntegrationTest` | `shouldFinalizeSubmitDecisionThroughRealMongoCoordinatorPath` | Local finalize reaches pending external state with snapshot/outbox/audit | No external finality claim |
| No false external finality | FDP-29 | `EvidenceGatedFinalizeCoordinatorTest` | recovery/finalize replay tests | Pending external is not confirmed external | External witness not modeled |
| Rollback-safe finalize | FDP-29 | `EvidenceGatedFinalizeCoordinatorIntegrationTest` | rollback failure tests | Failed finalize rolls back business/outbox/success audit | Mongo transaction model only |
| Local SUCCESS audit in transaction | FDP-29 | `RegulatedMutationLocalAuditPhaseWriterIntegrationTest` | local audit tests | Success audit persisted locally | External anchor separate |
| FINALIZE_RECOVERY_REQUIRED beats stale snapshot | FDP-29 | `EvidenceGatedFinalizeCoordinatorIntegrationTest` | recovery replay tests | Recovery state wins | Requires operator recovery |
| Stale owner cannot write command transition | FDP-32 | `RegulatedMutationLeaseFencingIntegrationTest` | stale owner tests | Stale write rejected | Covered for tested paths |
| Expired lease rejected | FDP-32 | `RegulatedMutationFencedCommandWriterTest` | expired lease tests | Fenced writer rejects stale/expired owner | Writer-level proof |
| No mutation after stale takeover | FDP-32 | `RegulatedMutationStaleWorkerExecutorIntegrationTest` | stale takeover tests | Business mutation count remains zero | Scenario based |
| Fenced writer conditional update | FDP-32 | `RegulatedMutationFencedCommandWriterTest` | transition guard tests | Command state changes only with active owner lease | Mongo conditional update proof |
| Owner-fenced renewal | FDP-33 | `RegulatedMutationLeaseRenewalIntegrationTest` | renewal owner tests | Only current owner renews | Mongo integration proof |
| Budget exceeded durable recovery | FDP-33 | `RegulatedMutationLeaseRenewalIntegrationTest` | budget tests | Recovery-required state durable | Existing policy limits apply |
| Invalid extension not budget exceeded | FDP-33 | `RegulatedMutationLeaseRenewalPolicyTest` | invalid extension tests | Validation failure is distinct | Policy-unit proof |
| Terminal/recovery states cannot renew | FDP-33 | `RegulatedMutationLeaseRenewalServiceTest` | terminal/recovery tests | Renewal rejected | Service-level proof |
| Metrics bounded | FDP-33 | `AlertServiceMetricsTest` | lease renewal label tests | Low-cardinality labels only | Does not validate dashboard rendering |
| Checkpoint renewal only at approved checkpoints | FDP-34 | `RegulatedMutationSafeCheckpointPolicyTest` | checkpoint policy tests | Unapproved checkpoints rejected | Policy table proof |
| Renewal failure stops execution | FDP-34 | `RegulatedMutationStaleWorkerExecutorIntegrationTest` | checkpoint budget tests | No later mutation after failed checkpoint | Executor-path proof |
| No mutation after failed checkpoint | FDP-34 | `RegulatedMutationStaleWorkerExecutorIntegrationTest` | checkpoint failure tests | Business/outbox/audit absent | Scenario based |
| Checkpoint renewal is not progress | FDP-34 | `AlertServiceMetricsTest` | checkpoint no-progress tests | No-progress metric is separate | Metrics proof |
| No generic heartbeat | FDP-34 | `RegulatedMutationArchitectureTest` | heartbeat/renewal guards | No public heartbeat endpoint | Source/type guard |
| Restart/recovery proof | FDP-35 | `RegulatedMutationRestartRecoveryProofTest` | modeled crash tests | Crash-like durable states do not false-succeed | Modeled, not kill -9 |
| API recovery behavior | FDP-35 | `AlertControllerTest` and recovery controller tests | recovery API tests | Recovery/pending states not exposed as committed success | Controller-level proof |
| Dashboards/alerts/runbooks | FDP-35 | `RegulatedMutationArchitectureTest` | docs guard | Operator specs exist and stay low-cardinality | Docs/spec proof |
| Rollback plan | FDP-35 | `RegulatedMutationCheckpointRenewalWiringTest` and docs guard | rollback tests/docs | Feature flags/model versions remain compatible | Operational plan proof |

