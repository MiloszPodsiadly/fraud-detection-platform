# FDP-28 Invariant Proof Traceability Matrix

Status: branch evidence.


FDP-28 is an invariant and modeled failure-injection proof pack. FDP-28.1 adds targeted concurrency and endpoint-boundary integration proofs. It includes targeted Mongo/Testcontainers integration proofs for concurrency and local transaction rollback.

FDP-28 does not perform real OS/JVM process kill chaos, Kafka broker chaos, external witness outage chaos, or distributed ACID validation. Full process-kill chaos testing is future FDP-28B/FDP-29 scope. It also does not claim distributed ACID or exactly-once broker delivery.

Authoritative merge CI must run FDP-28 integration tests with Docker/Testcontainers enabled. If Docker is unavailable and integration tests are skipped, the branch is not fully verified.

| Invariant | Why It Matters | Test Class | Test Method | Failure Scenario | Expected State | Remaining Limitation | Future FDP |
| --- | --- | --- | --- | --- | --- | --- | --- |
| No duplicate business mutation | Retry must not apply a second analyst decision or case update. | `RegulatedMutationCrashWindowInvariantTest` | `shouldReplayCompletedCommandWithoutSecondBusinessMutationOrSuccessAudit` | Same idempotency key, same payload, completed command with snapshot | Replayed response, `businessWrites=0`, no new SUCCESS audit | Modeled unit proof, not process-death chaos | FDP-28B |
| No idempotency replay mutation | Safe replay must read command truth only. | `RegulatedMutationCrashWindowInvariantTest` | `shouldRetryOnlySuccessAuditForPendingSnapshotWithoutSecondBusinessMutation` | `SUCCESS_AUDIT_PENDING` with snapshot | SUCCESS audit retry only, business mutation not rerun | Does not emulate JVM death between Mongo writes | FDP-28B |
| Conflicting idempotency rejected | Same key cannot rewrite intent, actor, or resource semantics. | `RegulatedMutationCrashWindowInvariantTest`; `FraudCaseMutationInvariantTest` | `shouldRejectConflictingIdempotencyPayloadWithoutMutationOrAudit`; `shouldBindFraudCaseUpdateIntentToResolvedActorAndIdempotencyKey` | Same key, different request hash | `ConflictingIdempotencyKeyException`, no mutation, no audit phase | HTTP 409 mapping covered by existing API tests, not repeated here | FDP-28B |
| Active lease duplicate is in-progress | Concurrent retry must not claim or execute twice. | `RegulatedMutationCrashWindowInvariantTest`; `ConcurrentIdempotencyIntegrationTest` | `shouldReturnInProgressForActiveProcessingDuplicateWithoutSecondClaim`; `shouldNotDuplicateBusinessMutationUnderConcurrentSameIdempotencyKey` | Same key during active `PROCESSING` lease and two concurrent same-key requests | In-progress/replay response, one business mutation, one outbox record, one SUCCESS audit phase | Integration proof uses real Mongo command claim and local transaction; not OS/JVM kill chaos | FDP-28B |
| ATTEMPTED failure blocks mutation | Durable attempted evidence is required before business state changes. | `RegulatedMutationCrashWindowInvariantTest` | `shouldRejectFraudCaseMutationBeforeBusinessWriteWhenAttemptedAuditFails` | ATTEMPTED audit write fails | `REJECTED` / `FAILED`, `businessWrites=0` | External anchor publication is separate FDP-24 path | FDP-29 |
| SUCCESS failure becomes degraded | Post-commit SUCCESS audit failure must be explicit, not hidden. | `RegulatedMutationCrashWindowInvariantTest` | `shouldPersistPostCommitDegradationWhenSubmitDecisionSuccessAuditFails` | SUCCESS audit fails after business mutation | `COMMITTED_DEGRADED`, `COMMITTED_EVIDENCE_INCOMPLETE`, degradation event | Does not prevent the post-commit window transactionally | FDP-29 |
| Unsafe state without snapshot requires recovery | Ambiguous crash windows must not replay as success. | `RegulatedMutationCrashWindowInvariantTest` | `shouldLeaveRecoveryRequiredWhenCrashWindowHasCommittedStateWithoutSnapshot` | Lease expired after `BUSINESS_COMMITTED` without snapshot | `RECOVERY_REQUIRED`, no mutation rerun | Recovery strategy coverage is action-specific | FDP-29 |
| Local transaction rollback protects business + outbox | Mongo local transaction must cover business write, outbox write, command marker/snapshot. | `RegulatedMutationTransactionRollbackIntegrationTest` | `shouldRollbackBusinessMutationWhenOutboxWriteFailsInsideRequiredTransaction`; `shouldRollbackCommandSnapshotBusinessAndOutboxWithoutSuccessAuditTruth` | Transaction throws after business/outbox/command writes | Alert unchanged, no outbox record, command restored, no SUCCESS audit | Mongo replica-set integration only, not distributed ACID | FDP-29 |
| Broker publish failure never becomes false `PUBLISHED` | Kafka publish ambiguity must not become fake delivery. | `FraudDecisionOutboxPublisherTest` | `shouldMarkConfirmationUnknownWhenKafkaPublishFailsAfterPublishAttempt`; `shouldMoveToConfirmationUnknownWhenBrokerAcceptedButLocalConfirmationFails` | Publish throws or mark-published fails after attempt | `PUBLISH_CONFIRMATION_UNKNOWN`, projection not falsely published | Synchronous publisher exception is conservatively ambiguous | FDP-29 |
| Terminal outbox failure is explicit | Undeliverable local outbox facts must degrade operational trust. | `FraudDecisionOutboxPublisherTest`; `SystemTrustLevelControllerTest`; `NoFalseHealthyInvariantTest` | `shouldMarkMissingPayloadAsTerminalAndNotPublish`; `shouldDowngradeFailClosedWhenOutboxHasTerminalFailure` | Missing payload or terminal failure count | `FAILED_TERMINAL`, trust not healthy | Max-attempt terminalization remains policy-specific | FDP-29 |
| Publish confirmation unknown is explicit | At-least-once delivery must not claim exactly-once confirmation. | `TransactionalOutboxFailureInjectionTest`; `FraudDecisionOutboxPublisherTest` | `shouldConvertStalePublishAttemptToConfirmationUnknownAndNotPublished`; `shouldMoveToConfirmationUnknownWhenBrokerAcceptedButLocalConfirmationFails`; `shouldMarkConfirmationUnknownWhenKafkaPublishFailsAfterPublishAttempt`; `shouldNotMarkUnknownWhenProjectionUpdateFailsAfterOutboxRecordPublished`; `shouldMarkMissingPayloadAsTerminalAndNotPublish`; `shouldNotPublishWhenConcurrentWorkerAlreadyClaimedRecord` | Stale publish attempted, confirmation persistence failure, publish exception, projection mismatch, missing payload, or concurrent worker race | `PUBLISH_CONFIRMATION_UNKNOWN`/`FAILED_TERMINAL` as appropriate, no false `PUBLISHED`, no duplicate publish | Manual/dual-control resolution remains operator workflow | FDP-29 |
| External evidence unavailable prevents healthy/confirmed | External anchor proof is required for bank healthy status. | `NoFalseHealthyInvariantTest`; `ExternalAuditIntegrityServiceTest` | `shouldNotReportHealthyWhenExternalCoverageIsUnavailable`; `shouldNotReportHealthyWhenRequiredExternalWitnessUnavailable`; `shouldReturnUnavailableCoverageWhenExternalHeadCannotBeProven` | Coverage/head/witness unavailable | Trust degraded, explicit reason code, no confirmed evidence | Real cloud S3/GCS/Azure adapter remains separate | FDP-23/FDP-29 |
| External evidence gaps prevent healthy | Missing ranges and local unverified statuses must degrade trust. | `NoFalseHealthyInvariantTest`; `ExternalAuditIntegrityServiceTest` | `shouldNotReportHealthyWhenExternalCoverageHasMissingRanges`; `shouldNotReportHealthyWhenRequiredPublicationFailuresExist`; `shouldNotReportHealthyWhenLocalStatusIsUnverified` | Missing range, required failure, local status unverified | Trust not healthy, counts surfaced | Coverage window is bounded | FDP-29 |
| External tamper/signature invalidity prevents confirmed evidence | Invalid external object or signature cannot be treated as valid. | `ExternalAuditIntegrityServiceTest`; `NoFalseHealthyInvariantTest` | `shouldDetectObjectStoreAnchorMismatchAsInvalid`; `shouldInvalidateUnsignedAndUnavailableSignaturesWhenTrustAuthoritySigningRequired`; `shouldNotReportHealthyWhenExternalSignatureStatePreventsValidIntegrity` | Tamper, unsigned required, signature unavailable | `INVALID`/degraded, no false valid | Trust Authority availability is external dependency | FDP-29 |
| Trust incident duplicate materialization prevented | Repeated signals must not create multiple active truths. | `TrustIncidentMaterializerTest`; `TrustIncidentConcurrentMaterializationIntegrationTest` | `shouldRecordDedupedMetricForExistingIncident`; `shouldCreateOnlyOneActiveTrustIncidentUnderConcurrentMaterialization` | Same type/source/fingerprint, including concurrent materializer executions | One active incident per `activeDedupeKey`, occurrence count increments, created/deduped metrics recorded | Concurrent proof uses real Mongo atomic upsert; not a multi-region race proof | FDP-28B |
| Trust incident failure does not false-resolve/ack | Operational incident state must not change if audit path is unavailable. | `TrustIncidentServiceTest`; `TrustIncidentMutationHandlerTest` | `shouldNotAcknowledgeWhenRegulatedAuditPathIsUnavailable`; `shouldRejectResolveWhenRepositorySaveFails` | ACK audit path unavailable or resolve save fails | No false ACK/RESOLVE success | End-to-end controller failure path covered separately | FDP-28B |
| Open critical incident prevents healthy | Control-plane critical incident must downgrade trust. | `NoFalseHealthyInvariantTest`; `TrustIncidentServiceTest` | `shouldNotReportHealthyWhenOpenCriticalTrustIncidentExists`; `shouldSummarizeWithoutWritingIncidents` | Open critical incident exists | Trust degraded, explicit incident counts | Incident severity policy is current local policy | FDP-29 |
| Sensitive read audit fail-closed in bank mode | Sensitive operational reads must not leak data when read audit is mandatory and unavailable. | `SensitiveReadAuditFailureInjectionTest`; `SensitiveReadEndpointFailClosedControllerTest`; `TrustIncidentControllerTest`; `SensitiveReadAuditServiceTest` | `shouldFailClosedForBankSensitiveReadWhenAuditPersistenceFails`; `shouldFailClosedWhenTrustLevelReadAuditFailsInBankMode`; `shouldFailClosedWhenTrustIncidentListAuditFailsInBankMode`; `shouldFailClosedWhenTrustSignalPreviewAuditFailsInBankMode`; `shouldFailClosedWhenRegulatedMutationInspectionAuditFailsInBankMode`; `shouldFailClosedWhenOutboxBacklogAuditFailsInBankMode`; `shouldFailClosedWhenAuditEvidenceExportAuditFailsInBankMode`; `shouldFailClosedWhenPolicyRequiresAuditPersistence` | Read audit persistence unavailable | 503, no sensitive DTO returned to caller | Controller methods may compute response before audit boundary; fail-closed prevents return | FDP-28B |
| Bank profile unsafe config rejected | Operators must not accidentally run local/dev partial semantics in bank/prod. | `BankProfileMisconfigurationMatrixTest`; `BankModeStartupGuardTest`; `AuthenticationStartupGuardTest` | See bank startup invariant matrix below | Missing transactions, outbox recovery, dual-control, external publication, trust authority, JWT, or demo-auth disabled incorrectly | Startup fails with explicit setting | Matrix references existing FDP-27 tests for auth-specific cases | FDP-29 |
| Projection never overrides authoritative source | Alert projection repair must derive from outbox record, not guess success. | `TransactionalOutboxFailureInjectionTest`; `OutboxRecoveryServiceTest` | `shouldRepairProjectionMismatchFromAuthoritativeOutboxRecordOnly`; `shouldRepairProjectionMismatchFromAuthoritativeOutboxRecord` | Projection mismatch | Projection repaired from authoritative outbox record | Does not introduce a separate projection event store | FDP-29 |
| No distributed ACID / exactly-once overclaim | Docs must not claim stronger claims than implemented. | Documentation review | `docs/architecture/alert_service_failure_windows.md`; `docs/fdp/fdp_29_evidence_gated_finalize_handoff.md` | Known post-commit window and broker ambiguity | Explicit limitations and FDP-29 handoff | Full pre-commit/finalize model not implemented | FDP-29 |

## Summary

FDP-28 proves two core reviewer blockers:

- retry paths do not execute a second authoritative business mutation;
- local Mongo transaction rollback protects business write, outbox write, and command truth in `REQUIRED` mode.

Remaining production limitation: FDP-28 documents and detects post-commit audit degradation. It does not eliminate that window transactionally.

## FDP-28.1 Integration Proof Addendum

| Invariant | Test Class | Test Method | Scenario | Expected State | Remaining Limitation |
| --- | --- | --- | --- | --- | --- |
| Concurrent same idempotency key does not duplicate mutation | `ConcurrentIdempotencyIntegrationTest` | `shouldNotDuplicateBusinessMutationUnderConcurrentSameIdempotencyKey` | Two same-key same-payload commits start together | One alert mutation, one outbox record, one SUCCESS audit phase, second caller receives explicit in-progress/replay state | Testcontainers Mongo replica set, not OS/JVM kill chaos |
| Concurrent trust incident materialization dedupes active truth | `TrustIncidentConcurrentMaterializationIntegrationTest` | `shouldCreateOnlyOneActiveTrustIncidentUnderConcurrentMaterialization` | Two materializers process identical `OUTBOX_TERMINAL_FAILURE` signal | One active incident, same `activeDedupeKey`, deterministic occurrence count and created/deduped metrics | Single Mongo replica-set proof, not multi-region |
| Broker accepted but local confirmation failed is ambiguous | `FraudDecisionOutboxPublisherTest` | `shouldMoveToConfirmationUnknownWhenBrokerAcceptedButLocalConfirmationFails` | Publisher succeeds, local mark-published update matches zero records | `PUBLISH_CONFIRMATION_UNKNOWN`, confirmation failure metric, no false `PUBLISHED` | Uses deterministic publisher fake instead of Kafka broker |
| Required external witness unavailable is not healthy | `NoFalseHealthyInvariantTest` | `shouldNotReportHealthyWhenRequiredExternalWitnessUnavailable` | Required external coverage reports witness unavailable | `FDP24_DEGRADED`, `coverageStatus=DEGRADED`, explicit reason code, no confirmed status | Real cloud adapter is still FDP-23/FDP-29 work |
| Endpoint-level sensitive reads fail closed | `SensitiveReadEndpointFailClosedControllerTest`; `TrustIncidentControllerTest` | `shouldFailClosedWhenTrustLevelReadAuditFailsInBankMode`; `shouldFailClosedWhenTrustIncidentListAuditFailsInBankMode`; `shouldFailClosedWhenTrustSignalPreviewAuditFailsInBankMode`; `shouldFailClosedWhenRegulatedMutationInspectionAuditFailsInBankMode`; `shouldFailClosedWhenOutboxBacklogAuditFailsInBankMode`; `shouldFailClosedWhenAuditEvidenceExportAuditFailsInBankMode` | Controller returns sensitive operational DTO but read audit is unavailable in bank fail-closed mode | 503 `Sensitive read audit unavailable.`, no DTO returned | Direct controller-level proof, not full HTTP serialization proof |
| Bank profile matrix is self-contained | `BankProfileMisconfigurationMatrixTest`; `BankModeStartupGuardTest`; `AuthenticationStartupGuardTest` | See matrix below | Unsafe prod/bank settings | Startup guard fails closed | Matrix references FDP-27 guard tests rather than duplicating all cases |

## Bank Startup Invariant Matrix

| Setting / Risk | Test Class | Test Method |
| --- | --- | --- |
| transaction-mode OFF in bank | `BankModeStartupGuardTest` | `shouldRejectBankModeWhenTransactionsAreOff` |
| transaction-mode OFF in prod | `BankModeStartupGuardTest` | `shouldRejectProdProfileWhenTransactionsAreOff` |
| no transaction manager | `BankModeStartupGuardTest` | `shouldRejectProdProfileWhenTransactionManagerIsMissing` |
| transaction probe disabled | `BankModeStartupGuardTest` | `shouldRejectBankModeWhenTransactionCapabilityProbeIsDisabled` |
| transaction probe failing | `BankModeStartupGuardTest` | `shouldRejectRequiredModeWhenTransactionCapabilityProbeFails` |
| outbox recovery disabled | `BankModeStartupGuardTest` | `shouldRejectProdProfileWhenOutboxRecoveryIsDisabled` |
| dual-control disabled | `BankModeStartupGuardTest` | `shouldRejectBankModeWhenOutboxConfirmationDualControlIsDisabled` |
| sensitive read audit fail-open | `BankModeStartupGuardTest`; `BankProfileMisconfigurationMatrixTest` | `shouldRejectBankModeWhenSensitiveReadAuditIsFailOpen`; `shouldRejectBankProfileWhenSensitiveReadAuditIsFailOpen` |
| external anchoring disabled | `BankModeStartupGuardTest` | `shouldRejectBankModeWhenExternalPublicationIsDisabled` |
| external anchoring required=false | `BankModeStartupGuardTest` | `shouldRejectBankModeWhenExternalPublicationIsNotRequired` |
| external anchoring fail-closed=false | `BankModeStartupGuardTest` | `shouldRejectBankModeWhenExternalPublicationIsFailOpen` |
| local/noop/in-memory/same-database sink | `BankModeStartupGuardTest`; `BankProfileMisconfigurationMatrixTest` | `shouldRejectBankModeWhenExternalPublicationUsesLocalSink`; `shouldRejectBankModeWhenExternalSinkIsDisabled`; `shouldRejectBankModeWhenExternalSinkIsNoop`; `shouldRejectBankModeWhenExternalSinkIsInMemory`; `shouldRejectBankModeWhenExternalSinkIsSameDatabase`; `shouldRejectBankProfileWhenExternalAnchorSinkIsLocal` |
| trust authority disabled | `BankModeStartupGuardTest` | `shouldRejectBankModeWhenTrustAuthorityIsDisabled` |
| signing-required=false | `BankModeStartupGuardTest`; `BankProfileMisconfigurationMatrixTest` | `shouldRejectBankModeWhenTrustAuthoritySigningIsNotRequired`; `shouldRejectBankProfileWhenTrustAuthoritySigningIsOptional` |
| JwtDecoder missing | `AuthenticationStartupGuardTest` | `shouldRejectBankProfileWhenJwtDecoderIsMissing`; `shouldRejectProdProfileWhenJwtDecoderIsMissing` |
| jwt.required=false | `AuthenticationStartupGuardTest` | `shouldRejectBankProfileWhenJwtRequiredIsFalse` |
| demo auth enabled | `AuthenticationStartupGuardTest` | `shouldRejectBankProfileWhenDemoAuthIsEnabled`; `shouldRejectProdProfileWhenDemoAuthIsEnabled` |
| DemoAuthFilter present | `AuthenticationStartupGuardTest` | `shouldRejectBankProfileWhenDemoAuthFilterBeanIsPresent` |
| trust refresh PARTIAL | `BankModeStartupGuardTest` | `shouldRejectBankModeWhenTrustIncidentRefreshModeIsPartial`; `shouldRejectProdProfileWhenTrustIncidentRefreshModeIsPartial` |

## How to Run FDP-28 Proof Pack

Fast invariant tests:

```bash
mvn -pl alert-service -am -Dgroups=invariant-proof -DexcludedGroups=integration test
```

Integration rollback/concurrency tests:

```bash
mvn -pl alert-service -am -Dgroups=integration test
```

Full FDP-28.1 proof suite:

```bash
mvn -pl alert-service -am -Dgroups=failure-injection,invariant-proof,integration test
```

These tags are selection aids. The normal module test suite remains authoritative before merge, and the authoritative merge CI must run the FDP-28 tagged proof suite with Docker/Testcontainers available.

Local developer machines may skip Docker/Testcontainers integration tests when Docker is unavailable. The authoritative CI merge gate must provide Docker and run those tests. A green local run without Docker does not equal full FDP-28 proof.

## Non-Claims

FDP-28.1 is still not full OS/JVM kill chaos, Kafka broker chaos, external witness outage chaos, distributed ACID, exactly-once Kafka, legal notarization, or Evidence-Gated Finalize. Those stronger claims remain future FDP scope.

