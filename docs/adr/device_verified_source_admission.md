# Device Verified Source Admission

Date: 2026-11-12

Status: repository-based architecture and implementation record, prepared for independent final review. It is not an approved institutional decision, production authorization, or merge approval.

## Decision and scope

Keep Device evidence behind three separate authorities: transaction source, Device observation, and source-population coverage. Existing transaction-source authority does not grant Device binding or history access. Implement a test-only private path that verifies those authorities, commits an immutable pre-event snapshot, and permits a diagnostic read only after the actual SCORED occurrence is published and matched. Keep Device admission and scoring disabled in production.

This repository record was written from checked-out code and tests. The Library scope, independent reviewer note, institutional masterplan R2, and an approved architecture decision were not found locally. Google Drive searches for the named records returned `Unknown tool({"name":"google_drive.search"})`. The canonical masterplan has not been edited. A reviewer must reconcile those sources before institutional approval.

## Authority boundaries

| Fact | Required authority and binding | Current disposition |
| --- | --- | --- |
| Transaction origin | Existing source signature, scoped key policy, durable receipt, and exact RAW/ENRICHED commitments | Source lineage only; not Device permission |
| Device/customer association | Separate bounded Device assertion with its own issuer, purpose, audience, source epoch, event identity, pseudonym and key version | Verified by the private test path; invalid, cross-scope or changed claims are denied before trusted writes |
| Population completeness | Separate coverage assertion over a named population, definition version, sequence origin/range, interval, inclusion/exclusion rules, generation and prior commitment | The signed statement is authenticated; it cannot prove that the upstream authority omitted no events |
| History identity | Institution-scoped keyed pseudonyms and explicit pseudonym key version | Raw customer/device values are not persisted as Device history keys; a hash is not physical-device attestation |
| SCORED occurrence | Actual published source occurrence with exact event ID, `createdAt`, fingerprint and initial scoring run | Private read only; guessed transaction or correlation IDs are insufficient |

## Implemented evidence flow

The verifier parses bounded signed bytes, enforces the fixed Ed25519 protocol and configured authority scope, then checks the source receipt and real RAW/ENRICHED lineage. The package-private admission adapter creates an opaque ticket for Stage A `stage`, `seal`, and `commit`. A caller-built observation, enum, request flag, guessed ID or ticket from another adapter cannot create trusted history.

Coverage manifests commit the authorized population definition, source epoch, sequence origin and range, time interval, watermark, generation, prior commitment, and ordered staged membership. Gaps, missing predecessors, unknown population, short windows, expired or revoked authority, and unavailable stores remain pending, unavailable, denied, or conflicting. They do not become an empty or safe history.

For event time `t`, the committed snapshot uses only `[t - 30 days, t)`. Stage B follows the existing source occurrence publication. It binds the exact real SCORED event ID, creation time and fingerprint to the immutable Stage A snapshot. Rescores and mismatches cannot read or replace that binding. The private reader verifies source and Device authority again, uses read-only scoped Mongo principals, and checks both observation and occurrence ID sets so one-sided deletion returns unavailable.

Only device novelty and device switching can be interpreted when their full history is available. Shared-device, country-mismatch, and proxy/VPN signals remain unsupported. The pure policy has no numeric score and does not participate in scoring, alerting, case, feedback, or recommendation decisions.

## Identity, schema and operational boundary

Every Device identity is scoped by `sourceIssuer`, institution token, source code, `sourceEpoch`, and `identityVersion=2`; event identities also bind the source event key. Candidate, coverage, observation, occurrence, fence and revocation records and their unique/window indexes enforce the same scope. Reuse of an event key or sequence in another epoch cannot inherit history, coverage, fences or revocations.

The six Device collections require the strict v2 schema and exact indexes. Startup rejects legacy/incompatible validators or indexes without repair. The private reader adds occurrence-window indexes; earlier databases need an owner-reviewed additive index migration. Earlier diagnostic validators may need a separately reviewed `collMod`. No migration or production DDL was run. Rollback keeps admission disabled; an old writer must not operate on v2 data. Backup restore, schema repair, and any reset are offline owner actions.

Tests use separate least-privilege source-owner, Device-writer and read-only diagnostic identities. They do not define production Mongo roles. Membership expiry is 31 days plus five minutes; occurrence and candidate receipts have 45-day horizons. Operational revocation removes active membership and invalidates coverage while retaining bounded receipts/tombstones. It is not legal erasure; no approved retention policy or general erasure workflow exists.

Detailed protocol bounds and test signer limitations are in [signed assertions](../architecture/device_signed_assertions.md); Stage A/schema details are in [verified admission](../architecture/device_verified_admission.md); Stage B roles and exact score binding are in [private diagnostics](../architecture/device_private_diagnostics.md); fault evidence and rollback limits are in [recovery and lifecycle](../architecture/device_resilience.md); operator actions are in the [runbook](../runbooks/device_risk_admission.md).

## Dated implementation-status addendum for roadmap R2

This section is a proposed version-controlled status amendment for the institutional roadmap R2. It preserves the roadmap's historical decisions; it is not copied into the canonical plan because that source is unavailable and its owner has not reviewed this record. The attested source-history ordering safeguard is already present in the checked-out merged baseline. The current working change adds a test-only trusted Device integration and private diagnostic. No Device code has been merged. Merchant admission, graph features, a future Engine Intelligence version, and later recommendation work remain separate decisions.

### Existing contract and release gates

The outer `TransactionScoredEvent` remains version 2. Strict ML evidence XOR/omission and compatibility behavior remain unchanged. Nested Engine Intelligence remains version 2, with four known identities (Rules, ML, Velocity, Device), at most four results, and at most 16,384 serialized bytes. Device remains optional, unavailable, and absent from the active engine registry. No public Device `AVAILABLE` result, new Kafka event, public API/UI field, fifth engine, fraud-score influence, or behavior change was added.

| Gate | Status | Evidence or outstanding decision |
| --- | --- | --- |
| Code merge | Pending | Changes remain uncommitted; independent review and the human merge decision are required. |
| Test-source demonstration | Passed, test only | Disposable signers, authenticated Mongo replica sets and Kafka Testcontainers prove selected boundaries, not a real institution. |
| Institutional onboarding | Blocked | Production issuer, population authority, key custody, Mongo roles and operational owners are absent. |
| Privacy and retention | Blocked | Legal purpose, retention, erasure and data-owner approval are absent. |
| Engine activation | Blocked | Both flags default off; their respective service fails startup when enabled without authorization. Device is not registered. |
| Public Engine Intelligence change | Blocked | Current nested v2 is unchanged. Any public Device result needs a separate contract and consumer decision. |
| Fraud-score influence | Blocked | Device evidence does not affect Rules/ML/Velocity, fraud score, risk level, alerts, cases, feedback or recommendations. |
| Device publication/recovery | Blocked | The existing source recovery path remains in use. No Device exact-payload outbox or public producer exists; the historical exact-payload NO-GO remains in force. |
| Capacity and operations | Not measured | The current capacity fixture, production workload, alerting, paging and SLOs were not run or configured. |

The exact-payload gate remains documented in [Device delivery and recovery](device_risk_exact_payload_delivery_gate.md). Merchant admission, graph work, a future Engine Intelligence version and later recommendation phases remain separate decisions.

## Verification evidence

The contract, compatibility, API/UI, privacy and individual fail-start selection ran **125 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**:

```powershell
mvn "-Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo" -pl common-events,feature-enricher-service,fraud-scoring-service,alert-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=DeviceRiskAdmissionConfigurationTest,EngineIntelligenceConditionalRuntimeGraphTest,EngineIntelligenceDefaultDisabledContextSafetyTest,DeviceRiskSignalEngineTest,ProducerEngineIntelligenceDisabledOutputContractTest,TransactionScoredEventMlPredictionEvidenceContractTest,TransactionScoredEventCompatibilityTest,TransactionScoredEventFixtureCompatibilityTest,TransactionScoredEventEngineIntelligenceCompatibilityTest,EngineIntelligencePayloadLimitsTest,FraudEngineContractScopeGuardTest,MlPredictionEvidenceContractTest,EngineIntelligenceNoApiUiExposureTest,EngineIntelligenceNoLoggingExposureTest,EngineIntelligenceRawLeakageTest,EngineIntelligenceNoDecisioningContractTest,EngineIntelligenceDownstreamIsolationTest,TransactionScoredEventListenerEngineIntelligenceToleranceTest,AlertServiceTransactionScoredEventDeserializationCompatibilityTest" test
```

The additional Engine Intelligence v2, scored-event contract, and Device evidence selection ran **30 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**:

```powershell
mvn "-Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo" -pl common-events -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=EngineIntelligenceContractTest,TransactionScoredEventEngineIntelligenceContractTest,DeviceRiskEvidenceContractTest" test
```

The targeted Rules, ML, Velocity and baseline-output selection ran **45 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**:

```powershell
mvn "-Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo" -pl fraud-scoring-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=TransactionFraudScoringServiceTest,TransactionFraudScoringOriginClassificationTest,RuleBasedFraudScoringEngineTest,MlFraudScoringEngineTest,CompositeFraudScoringEngineTest,VelocitySignalEngineTest,ProducerEngineIntelligenceDisabledOutputContractTest" test
```

After adding explicit combined-flag assertions, the startup-guard selection ran **19 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. It checks admission and scoring flags separately and together in their respective service contexts.

```powershell
mvn "-Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo" -pl feature-enricher-service,fraud-scoring-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=DeviceRiskAdmissionConfigurationTest,EngineIntelligenceConditionalRuntimeGraphTest" test
```

The current Testcontainers RAW-ordering, SCORED-recovery/privacy and alert-idempotency selection ran **6 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS** with real Mongo and Kafka containers:

```powershell
mvn "-Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo" -pl transaction-ingest-service,feature-enricher-service,fraud-scoring-service,alert-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=SourceLineagePipelineIntegrationTest#pendingEarlierRawCannotBeOvertakenBySameCustomerAdmissionOrRecovery+restartedPublisherReplaysEncryptedRawIntentToKafkaWithAtLeastOnceExactIdentity,SourceScoredOccurrenceIntegrationTest#lostKafkaAckRemainsUnavailableUntilRestartReplaysExactlyTheSameFact+sourceProofAndPiiStayOutOfIngestResponsesApplicationLogsAndMetricTags,ScoringOccurrenceProcessingMongoIntegrationTest#identicalHighReplayAfterLowDoesNotDuplicateBusinessEffects+newerLowOccurrenceRemovesCurrentSuspiciousProjectionAndPreservesHistoricalWorkflow" test
```

The feedback, recommendations, and Merchant regression selection ran **64 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. It covered feedback write/idempotency, advisory recommendation behavior, Merchant fail-closed admission, and Merchant ordering/snapshot replay.

```powershell
mvn "-Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo" -pl feature-enricher-service,fraud-scoring-service,alert-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=AnalystRecommendationServiceTest,AnalystRecommendationApiArchitectureGuardTest,FraudFeedbackServiceTest,FraudFeedbackPostFlowIntegrationTest#postSuccessPersistsFeedbackAndOutboxIntentBeforeReturning201+postDuplicateReturns409BeforeSaveOrOutbox,MongoMerchantObservationStoreIntegrationTest#missingAuthorityRejectsBeforeMongoAndTheApplicationHasNoStoreOrVerifier+reverseArrivalWaitsForPredecessorsAndReplayKeepsTheExactAsOfSnapshot" test
```

Earlier signed-admission, private-diagnostic, concurrency, revocation and lifecycle evidence is recorded in the linked documents. Full Maven `verify`, current capacity benchmarking, and production deployment tests were not run.

## Open review items

- Retrieve the authoritative Library scope, reviewer note and institutional masterplan; reconcile them with this repository record.
- Obtain independent final review of the complete working diff and make no merge or activation decision from test evidence alone.
- Keep external issuer/population truth, privacy/retention, production roles/index migration, monitoring and operational recovery blocked until their owners approve them.
