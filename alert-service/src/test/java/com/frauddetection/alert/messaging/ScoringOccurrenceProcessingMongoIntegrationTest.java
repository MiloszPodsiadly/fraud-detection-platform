package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionMapper;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionWriteFence;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionWriteResult;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionService;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModelMapper;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadService;
import com.frauddetection.alert.evidence.AlertEvidenceSnapshotProjectionService;
import com.frauddetection.alert.evidence.AlertEvidenceSnapshotProperties;
import com.frauddetection.alert.evidence.ScoringEvidenceSnapshotMapper;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueProperties;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudAlertEventMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.FraudAlertOutboxRecord;
import com.frauddetection.alert.outbox.FraudAlertOutboxWriter;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionProjectionWriter;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.mutation.fraudcase.FraudCaseUpdateMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.alert.service.AlertCaseFactory;
import com.frauddetection.alert.service.AlertManagementService;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.alert.service.FraudCaseQueryService;
import com.frauddetection.alert.service.ScoredTransactionSearchPolicy;
import com.frauddetection.alert.service.SubmitDecisionRegulatedMutationService;
import com.frauddetection.alert.service.TransactionMonitoringService;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.alert.suspicious.SuspiciousTransactionDocument;
import com.frauddetection.alert.suspicious.SuspiciousTransactionProjectionService;
import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlModelIdentity;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceV1;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.mongodb.MongoException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@Testcontainers
class ScoringOccurrenceProcessingMongoIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-10-03T12:00:00Z");
    private static final String TRANSACTION_ID = "transaction-occurrence-1";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private TransactionTemplate transactionTemplate;
    private TransactionMonitoringService monitoringService;
    private EngineIntelligenceProjectionService engineIntelligenceProjectionService;
    private AlertManagementService alertManagementService;
    private TransactionScoredEventListener listener;
    private MlPredictionEvidenceEventListener evidenceListener;
    private MlPredictionEvidenceProjectionRepository evidenceRepository;
    private ScoredTransactionRepository scoredTransactionRepository;
    private EngineIntelligenceReadService engineIntelligenceReadService;

    @BeforeEach
    void setUp() {
        String databaseName = "scoring_occurrence_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                MONGO.getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        transactionTemplate = new TransactionTemplate(new MongoTransactionManager(databaseFactory));
        createCurrentProjectionIndexes();

        MongoRepositoryFactory repositories = new MongoRepositoryFactory(mongoTemplate);
        scoredTransactionRepository = repositories.getRepository(ScoredTransactionRepository.class);
        EngineIntelligenceProjectionRepository engineIntelligence =
                repositories.getRepository(EngineIntelligenceProjectionRepository.class);
        evidenceRepository = repositories.getRepository(MlPredictionEvidenceProjectionRepository.class);
        AlertRepository alerts = repositories.getRepository(AlertRepository.class);
        SuspiciousTransactionRepository suspiciousTransactions =
                repositories.getRepository(SuspiciousTransactionRepository.class);
        FraudCaseRepository fraudCases = repositories.getRepository(FraudCaseRepository.class);
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());

        monitoringService = new TransactionMonitoringService(
                scoredTransactionRepository,
                new ScoredTransactionDocumentMapper(),
                mongoTemplate,
                new ScoredTransactionSearchPolicy(),
                new ScoredTransactionProjectionWriter(mongoTemplate)
        );
        engineIntelligenceProjectionService = new EngineIntelligenceProjectionService(
                new com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionWriteFence(mongoTemplate),
                new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy()),
                metrics,
                scoredTransactionRepository
        );
        FraudCaseManagementService fraudCaseManagement = new FraudCaseManagementService(
                fraudCases,
                scoredTransactionRepository,
                mock(AnalystActorResolver.class),
                new FraudCaseUpdateMutationHandler(fraudCases, metrics),
                mock(RegulatedMutationCoordinator.class),
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                new FraudCaseQueryService(
                        fraudCases,
                        mock(FraudCaseSearchRepository.class),
                        new FraudCaseWorkQueueProperties(Duration.ofHours(24), "scoring-occurrence-test-secret")
                )
        );
        alertManagementService = new AlertManagementService(
                alerts,
                new AlertDocumentMapper(),
                new FraudAlertEventMapper(),
                new AlertCaseFactory(),
                new AlertEvidenceSnapshotProjectionService(
                        new ScoringEvidenceSnapshotMapper(),
                        new AlertEvidenceSnapshotProperties(null),
                        metrics
                ),
                new FraudAlertOutboxWriter(mongoTemplate),
                fraudCaseManagement,
                new SuspiciousTransactionProjectionService(suspiciousTransactions, metrics),
                metrics,
                mock(SubmitDecisionRegulatedMutationService.class)
        );
        listener = new TransactionScoredEventListener(
                alertManagementService,
                monitoringService,
                new KafkaTopicProperties(
                        "transactions.scored",
                        "fraud.alerts",
                        "fraud.decisions",
                        "transactions.dead-letter"
                )
        );
        evidenceListener = new MlPredictionEvidenceEventListener(new MlPredictionEvidenceProjectionService(
                evidenceRepository,
                new EngineIntelligenceProjectionPolicy(),
                metrics
        ));
        engineIntelligenceReadService = new EngineIntelligenceReadService(
                scoredTransactionRepository,
                engineIntelligence,
                new EngineIntelligenceReadModelMapper()
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (databaseFactory != null) {
            databaseFactory.destroy();
        }
    }

    @Test
    void olderThenNewerSupersedesEveryCurrentProjectionAndPreservesHistoricalEvidence() {
        TransactionScoredEvent earlier = event("event-a", BASE_TIME, 0.81d, "model-a");
        TransactionScoredEvent newer = event("event-b", BASE_TIME.plusSeconds(1), 0.96d, "model-b");

        process(earlier);
        processEvidence(earlier);
        process(newer);
        processEvidence(newer);
        process(newer);
        processEvidence(newer);

        assertCurrentOccurrence("event-b", 0.96d, "model-b");
        assertHistoricalEvidence(earlier, newer);
        assertExactlyOneBusinessEffect();
    }

    @Test
    void newerThenOlderKeepsNewerCurrentStateAndImmutableHistoricalEvidence() {
        TransactionScoredEvent newer = event("event-b", BASE_TIME.plusSeconds(1), 0.96d, "model-b");
        TransactionScoredEvent earlier = event("event-a", BASE_TIME, 0.81d, "model-a");
        process(newer);
        processEvidence(newer);
        process(earlier);
        processEvidence(earlier);

        assertCurrentOccurrence("event-b", 0.96d, "model-b");
        assertHistoricalEvidence(earlier, newer);
        assertExactlyOneBusinessEffect();
    }

    @Test
    void newerLowOccurrenceRemovesCurrentSuspiciousProjectionAndPreservesHistoricalWorkflow() {
        TransactionScoredEvent earlierHigh = event("event-a", BASE_TIME, 0.81d, "model-a");
        TransactionScoredEvent newerLow = event(
                "event-b",
                BASE_TIME.plusSeconds(1),
                0.18d,
                "model-b",
                RiskLevel.LOW,
                false,
                null
        );

        process(earlierHigh);
        AlertDocument historicalAlert = mongoTemplate.findOne(new Query(), AlertDocument.class);
        FraudCaseDocument historicalCase = mongoTemplate.findOne(new Query(), FraudCaseDocument.class);
        FraudAlertOutboxRecord historicalOutbox = mongoTemplate.findOne(new Query(), FraudAlertOutboxRecord.class);
        assertThat(historicalAlert).isNotNull();
        assertThat(historicalCase).isNotNull();
        assertThat(historicalOutbox).isNotNull();

        process(newerLow);
        process(earlierHigh);

        assertCurrentScoredOccurrence("event-b", RiskLevel.LOW, false);
        assertThat(mongoTemplate.count(new Query(), SuspiciousTransactionDocument.class)).isZero();
        assertThat(mongoTemplate.findOne(new Query(), AlertDocument.class))
                .satisfies(alert -> {
                    assertThat(alert).isNotNull();
                    assertThat(alert.getAlertId()).isEqualTo(historicalAlert.getAlertId());
                    assertThat(alert.getSourceEventId()).isEqualTo("event-a");
                    assertThat(alert.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
                    assertThat(alert.getAnalystDecision()).isEqualTo(historicalAlert.getAnalystDecision());
                    assertThat(alert.getDecidedAt()).isEqualTo(historicalAlert.getDecidedAt());
                });
        assertThat(mongoTemplate.findOne(new Query(), FraudCaseDocument.class))
                .satisfies(fraudCase -> {
                    assertThat(fraudCase).isNotNull();
                    assertThat(fraudCase.getCaseId()).isEqualTo(historicalCase.getCaseId());
                    assertThat(fraudCase.getStatus()).isEqualTo(historicalCase.getStatus());
                    assertThat(fraudCase.getAnalystId()).isEqualTo(historicalCase.getAnalystId());
                    assertThat(fraudCase.getClosedAt()).isEqualTo(historicalCase.getClosedAt());
                    assertThat(fraudCase.getClosureReason()).isEqualTo(historicalCase.getClosureReason());
                    assertThat(fraudCase.getDecidedAt()).isEqualTo(historicalCase.getDecidedAt());
                });
        assertThat(mongoTemplate.findOne(new Query(), FraudAlertOutboxRecord.class))
                .satisfies(outbox -> {
                    assertThat(outbox).isNotNull();
                    assertThat(outbox.getEventId()).isEqualTo(historicalOutbox.getEventId());
                    assertThat(outbox.getAlertId()).isEqualTo(historicalOutbox.getAlertId());
                });
        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), FraudCaseDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), FraudAlertOutboxRecord.class)).isEqualTo(1L);
    }

    @Test
    void newerHighOccurrenceCreatesCurrentSuspiciousProjectionAfterLow() {
        TransactionScoredEvent earlierLow = event(
                "event-a", BASE_TIME, 0.18d, "model-a", RiskLevel.LOW, false, null
        );
        TransactionScoredEvent newerHigh = event("event-b", BASE_TIME.plusSeconds(1), 0.96d, "model-b");

        process(earlierLow);
        assertThat(mongoTemplate.count(new Query(), SuspiciousTransactionDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isZero();

        process(newerHigh);

        assertCurrentOccurrence("event-b", 0.96d, "model-b");
        assertExactlyOneBusinessEffect();
    }

    @Test
    void staleHighCannotRecreateProjectionRemovedByNewerLow() {
        TransactionScoredEvent earlierHigh = event("event-a", BASE_TIME, 0.81d, "model-a");
        TransactionScoredEvent newerLow = event(
                "event-b", BASE_TIME.plusSeconds(1), 0.18d, "model-b", RiskLevel.LOW, false, null
        );

        process(earlierHigh);
        process(newerLow);
        process(earlierHigh);

        assertCurrentScoredOccurrence("event-b", RiskLevel.LOW, false);
        assertThat(mongoTemplate.count(new Query(), SuspiciousTransactionDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), FraudCaseDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), FraudAlertOutboxRecord.class)).isEqualTo(1L);
    }

    @Test
    void identicalHighReplayAfterLowDoesNotDuplicateBusinessEffects() {
        TransactionScoredEvent earlierLow = event(
                "event-a", BASE_TIME, 0.18d, "model-a", RiskLevel.LOW, false, null
        );
        TransactionScoredEvent newerHigh = event("event-b", BASE_TIME.plusSeconds(1), 0.96d, "model-b");

        process(earlierLow);
        process(newerHigh);
        process(newerHigh);

        assertCurrentOccurrence("event-b", 0.96d, "model-b");
        assertExactlyOneBusinessEffect();
    }

    @Test
    void identicalLowRedeliveryAfterListenerRestartKeepsProjectionAbsent() {
        TransactionScoredEvent earlierHigh = event("event-a", BASE_TIME, 0.81d, "model-a");
        TransactionScoredEvent newerLow = event(
                "event-b", BASE_TIME.plusSeconds(1), 0.18d, "model-b", RiskLevel.LOW, false, null
        );
        process(earlierHigh);
        process(newerLow);
        TransactionScoredEventListener restartedListener = new TransactionScoredEventListener(
                alertManagementService,
                monitoringService,
                new KafkaTopicProperties(
                        "transactions.scored",
                        "fraud.alerts",
                        "fraud.decisions",
                        "transactions.dead-letter"
                )
        );

        transactionTemplate.executeWithoutResult(status -> restartedListener.onMessage(newerLow, null));

        assertCurrentScoredOccurrence("event-b", RiskLevel.LOW, false);
        assertThat(mongoTemplate.count(new Query(), SuspiciousTransactionDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), FraudAlertOutboxRecord.class)).isEqualTo(1L);
    }

    @Test
    void newerOccurrenceWithoutDiagnosticsCannotInheritPreviousModelIdentity() {
        TransactionScoredEvent earlier = event("event-a", BASE_TIME, 0.81d, "model-a");
        TransactionScoredEvent newerWithoutDiagnostics = event(
                "event-b",
                BASE_TIME.plusSeconds(1),
                0.42d,
                "model-b",
                null
        );

        process(earlier);
        processEvidence(earlier);
        process(newerWithoutDiagnostics);

        ScoredTransactionDocument current = mongoTemplate.findById(TRANSACTION_ID, ScoredTransactionDocument.class);
        EngineIntelligenceProjection retainedOlderProjection = mongoTemplate.findById(
                TRANSACTION_ID,
                EngineIntelligenceProjection.class
        );
        assertThat(current).isNotNull();
        assertThat(current.getSourceEventId()).isEqualTo("event-b");
        assertThat(retainedOlderProjection).isNotNull();
        assertThat(retainedOlderProjection.getSourceEventId()).isEqualTo("event-a");
        assertThat(engineIntelligenceReadService.read(TRANSACTION_ID).available()).isFalse();
        assertHistoricalEvidence(earlier);
    }

    @Test
    void newerOccurrenceWithInvalidDiagnosticsCannotInheritPreviousModelIdentity() {
        TransactionScoredEvent earlier = event("event-a", BASE_TIME, 0.81d, "model-a");
        EngineIntelligenceSummary invalidDiagnostics = spy(engineIntelligence(
                BASE_TIME.plusSeconds(1),
                "model-b",
                0.42d
        ));
        when(invalidDiagnostics.contractVersion()).thenReturn(EngineIntelligenceSummary.CONTRACT_VERSION + 1);
        TransactionScoredEvent newer = event(
                "event-b",
                BASE_TIME.plusSeconds(1),
                0.42d,
                "model-b",
                invalidDiagnostics
        );

        process(earlier);
        process(newer);

        assertThat(mongoTemplate.findById(TRANSACTION_ID, ScoredTransactionDocument.class))
                .extracting(ScoredTransactionDocument::getSourceEventId)
                .isEqualTo("event-b");
        assertThat(mongoTemplate.findById(TRANSACTION_ID, EngineIntelligenceProjection.class))
                .extracting(EngineIntelligenceProjection::getSourceEventId)
                .isEqualTo("event-a");
        assertThat(engineIntelligenceReadService.read(TRANSACTION_ID).available()).isFalse();
    }

    @Test
    void diagnosticWriteFailureCannotRollBackCommittedBaselineBusinessResult() {
        TransactionScoredEvent event = event("event-b", BASE_TIME.plusSeconds(1), 0.96d, "model-b");
        transactionTemplate.executeWithoutResult(status -> listener.onMessage(event, null));

        com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionWriteFence unavailableWriteFence =
                mock(com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionWriteFence.class);
        when(unavailableWriteFence.write(any())).thenThrow(new IllegalStateException("diagnostic store unavailable"));
        EngineIntelligenceProjectionService failingProjection = new EngineIntelligenceProjectionService(
                unavailableWriteFence,
                new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy()),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                scoredTransactionRepository
        );
        EngineIntelligenceProjectionEventListener failingListener =
                new EngineIntelligenceProjectionEventListener(
                        failingProjection,
                        mock(com.frauddetection.alert.engineintelligence.EngineIntelligencePendingProjectionService.class)
                );

        assertThatThrownBy(() -> failingListener.onMessage(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("ENGINE_INTELLIGENCE_PROJECTION_STORE_UNAVAILABLE");

        assertThat(mongoTemplate.findById(TRANSACTION_ID, ScoredTransactionDocument.class))
                .extracting(ScoredTransactionDocument::getSourceEventId)
                .isEqualTo("event-b");
        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), SuspiciousTransactionDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), FraudCaseDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), FraudAlertOutboxRecord.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), EngineIntelligenceProjection.class)).isZero();
    }

    @Test
    void conflictingReplayCannotMutateCurrentStateOrAcceptedEvidence() {
        TransactionScoredEvent accepted = event("event-b", BASE_TIME.plusSeconds(1), 0.96d, "model-b");
        TransactionScoredEvent conflict = event("event-b", BASE_TIME.plusSeconds(1), 0.51d, "model-conflict");
        process(accepted);
        processEvidence(accepted);

        assertThatThrownBy(() -> process(conflict))
                .isInstanceOf(ScoringOccurrenceConflictException.class);
        assertThatThrownBy(() -> processEvidence(conflict))
                .isInstanceOf(MlPredictionEvidencePermanentProcessingException.class);

        assertCurrentOccurrence("event-b", 0.96d, "model-b");
        assertHistoricalEvidence(accepted);
        assertExactlyOneBusinessEffect();
    }

    @Test
    void identicalReplayAfterDependentFailureCompletesEffectsWithoutDuplication() {
        TransactionScoredEvent event = event("event-replay", BASE_TIME, 0.93d, "model-replay");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            monitoringService.recordScoredTransaction(event);
            throw new IllegalStateException("injected failure before alert processing");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(mongoTemplate.count(new Query(), ScoredTransactionDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), EngineIntelligenceProjection.class)).isZero();

        processEvidence(event);
        process(event);
        processEvidence(event);

        assertCurrentOccurrence("event-replay", 0.93d, "model-replay");
        assertHistoricalEvidence(event);
        assertExactlyOneBusinessEffect();
    }

    @Test
    void suspendedOlderWorkerCannotOverwriteNewerOccurrenceAfterConcurrentResume() throws Exception {
        TransactionScoredEvent earlier = event("event-a", BASE_TIME, 0.81d, "model-a");
        TransactionScoredEvent newer = event("event-b", BASE_TIME.plusSeconds(1), 0.96d, "model-b");
        CountDownLatch earlierAdmitted = new CountDownLatch(1);
        CountDownLatch resumeEarlier = new CountDownLatch(1);
        CountDownLatch newerAttempted = new CountDownLatch(1);
        TransactionScoredEventListener coordinatedListener = coordinatedListener(
                earlierAdmitted,
                resumeEarlier,
                newerAttempted
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> earlierResult = executor.submit(() -> processWithTransientRetry(earlier, coordinatedListener));
            assertThat(earlierAdmitted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> newerResult = executor.submit(() -> processWithTransientRetry(newer, coordinatedListener));
            assertThat(newerAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            resumeEarlier.countDown();

            earlierResult.get(20, TimeUnit.SECONDS);
            newerResult.get(20, TimeUnit.SECONDS);
            processEngineIntelligence(earlier);
            processEngineIntelligence(newer);
            processEvidence(earlier);
            processEvidence(newer);

            assertCurrentOccurrence("event-b", 0.96d, "model-b");
            assertHistoricalEvidence(earlier, newer);
            assertExactlyOneBusinessEffect();
        } finally {
            resumeEarlier.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentNewerLowWinsWithoutLeavingStaleCurrentProjectionOrPartialAlertEffects() throws Exception {
        TransactionScoredEvent earlierHigh = event("event-a", BASE_TIME, 0.81d, "model-a");
        TransactionScoredEvent newerLow = event(
                "event-b", BASE_TIME.plusSeconds(1), 0.18d, "model-b", RiskLevel.LOW, false, null
        );
        CountDownLatch earlierAdmitted = new CountDownLatch(1);
        CountDownLatch resumeEarlier = new CountDownLatch(1);
        CountDownLatch newerAttempted = new CountDownLatch(1);
        TransactionScoredEventListener coordinatedListener = coordinatedListener(
                earlierAdmitted,
                resumeEarlier,
                newerAttempted
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> earlierResult = executor.submit(() -> processWithTransientRetry(earlierHigh, coordinatedListener));
            assertThat(earlierAdmitted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> newerResult = executor.submit(() -> processWithTransientRetry(newerLow, coordinatedListener));
            assertThat(newerAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            resumeEarlier.countDown();

            earlierResult.get(20, TimeUnit.SECONDS);
            newerResult.get(20, TimeUnit.SECONDS);

            assertCurrentScoredOccurrence("event-b", RiskLevel.LOW, false);
            assertThat(mongoTemplate.count(new Query(), SuspiciousTransactionDocument.class)).isZero();
            // Admission is observed before commit, so the earlier HIGH may commit atomically or roll back completely.
            long alertCount = mongoTemplate.count(new Query(), AlertDocument.class);
            long outboxCount = mongoTemplate.count(new Query(), FraudAlertOutboxRecord.class);
            assertThat(alertCount).isIn(0L, 1L);
            assertThat(outboxCount).isEqualTo(alertCount);
            if (alertCount == 1L) {
                AlertDocument alert = mongoTemplate.findOne(new Query(), AlertDocument.class);
                FraudAlertOutboxRecord outbox = mongoTemplate.findOne(new Query(), FraudAlertOutboxRecord.class);
                assertThat(alert).isNotNull();
                assertThat(alert.getSourceEventId()).isEqualTo("event-a");
                assertThat(outbox).isNotNull();
                assertThat(outbox.getAlertId()).isEqualTo(alert.getAlertId());
                assertThat(outbox.getTransactionId()).isEqualTo(TRANSACTION_ID);
                assertThat(outbox.getPayload()).isNotNull();
                assertThat(outbox.getPayload().alertId()).isEqualTo(alert.getAlertId());
            }
        } finally {
            resumeEarlier.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void delayedValidatedDiagnosticsUseNanosecondOrderWithinSameMillisecond() throws Exception {
        Instant earlierCreatedAt = BASE_TIME.plusNanos(100);
        Instant newerCreatedAt = BASE_TIME.plusNanos(200);
        TransactionScoredEvent earlier = event("z-event", earlierCreatedAt, 0.81d, "model-a");
        TransactionScoredEvent newer = event("a-event", newerCreatedAt, 0.96d, "model-b");
        transactionTemplate.executeWithoutResult(status -> listener.onMessage(earlier, null));

        CountDownLatch earlierReachedWriteFence = new CountDownLatch(1);
        CountDownLatch resumeEarlier = new CountDownLatch(1);
        EngineIntelligenceProjectionWriteFence coordinatedFence = new EngineIntelligenceProjectionWriteFence(
                mongoTemplate
        ) {
            @Override
            public EngineIntelligenceProjectionWriteResult write(EngineIntelligenceProjection candidate) {
                if (earlier.eventId().equals(candidate.getSourceEventId())) {
                    earlierReachedWriteFence.countDown();
                    await(resumeEarlier);
                }
                return super.write(candidate);
            }
        };
        EngineIntelligenceProjectionService coordinatedProjectionService = new EngineIntelligenceProjectionService(
                coordinatedFence,
                new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy()),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                scoredTransactionRepository
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> earlierProjection = executor.submit(() ->
                    coordinatedProjectionService.projectCurrentOccurrence(earlier)
            );
            assertThat(earlierReachedWriteFence.await(10, TimeUnit.SECONDS)).isTrue();

            transactionTemplate.executeWithoutResult(status -> listener.onMessage(newer, null));
            assertThat(engineIntelligenceProjectionService.projectCurrentOccurrence(newer).projection()).isPresent();
            resumeEarlier.countDown();
            earlierProjection.get(20, TimeUnit.SECONDS);

            EngineIntelligenceProjection stored = mongoTemplate.findById(
                    TRANSACTION_ID,
                    EngineIntelligenceProjection.class
            );
            assertThat(stored).isNotNull();
            assertThat(stored.getSourceEventId()).isEqualTo(newer.eventId());
            assertThat(stored.getSourceEventCreatedAt()).isEqualTo(newerCreatedAt);
            assertThat(scoredTransactionRepository.findById(TRANSACTION_ID).orElseThrow().getSourceEventId())
                    .isEqualTo(newer.eventId());
            assertThat(engineIntelligenceReadService.read(TRANSACTION_ID).available()).isTrue();
        } finally {
            resumeEarlier.countDown();
            executor.shutdownNow();
        }
    }

    private TransactionScoredEventListener coordinatedListener(
            CountDownLatch earlierAdmitted,
            CountDownLatch resumeEarlier,
            CountDownLatch newerAttempted
    ) {
        TransactionMonitoringUseCase coordinatedMonitoring = mock(TransactionMonitoringUseCase.class);
        when(coordinatedMonitoring.recordScoredTransaction(any(TransactionScoredEvent.class))).thenAnswer(invocation -> {
            TransactionScoredEvent event = invocation.getArgument(0);
            if ("event-b".equals(event.eventId())) {
                newerAttempted.countDown();
            }
            var admission = monitoringService.recordScoredTransaction(event);
            if ("event-a".equals(event.eventId()) && admission.isCurrentOccurrence()) {
                earlierAdmitted.countDown();
                await(resumeEarlier);
            }
            return admission;
        });
        return new TransactionScoredEventListener(
                alertManagementService,
                coordinatedMonitoring,
                new KafkaTopicProperties(
                        "transactions.scored",
                        "fraud.alerts",
                        "fraud.decisions",
                        "transactions.dead-letter"
                )
        );
    }

    private void process(TransactionScoredEvent event) {
        transactionTemplate.executeWithoutResult(status -> listener.onMessage(event, null));
        processEngineIntelligence(event);
    }

    private void processEngineIntelligence(TransactionScoredEvent event) {
        transactionTemplate.executeWithoutResult(status ->
                engineIntelligenceProjectionService.projectCurrentOccurrence(event)
        );
    }

    private void processWithTransientRetry(
            TransactionScoredEvent event,
            TransactionScoredEventListener eventListener
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> eventListener.onMessage(event, null));
                return;
            } catch (RuntimeException exception) {
                if (!isTransientTransactionFailure(exception)) {
                    throw exception;
                }
                lastFailure = exception;
            }
        }
        throw lastFailure == null ? new IllegalStateException("TRANSIENT_TRANSACTION_RETRY_EXHAUSTED") : lastFailure;
    }

    private void processEvidence(TransactionScoredEvent event) {
        evidenceListener.onMessage(event);
    }

    private boolean isTransientTransactionFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof MongoException mongoException
                    && mongoException.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void assertCurrentOccurrence(String eventId, double score, String modelVersion) {
        ScoredTransactionDocument scored = mongoTemplate.findById(TRANSACTION_ID, ScoredTransactionDocument.class);
        AlertDocument alert = mongoTemplate.findOne(new Query(), AlertDocument.class);
        SuspiciousTransactionDocument suspicious = mongoTemplate.findOne(new Query(), SuspiciousTransactionDocument.class);
        FraudCaseDocument fraudCase = mongoTemplate.findOne(new Query(), FraudCaseDocument.class);
        EngineIntelligenceProjection intelligence = mongoTemplate.findById(
                TRANSACTION_ID,
                EngineIntelligenceProjection.class
        );

        assertThat(scored).isNotNull();
        assertThat(scored.getSourceEventId()).isEqualTo(eventId);
        assertThat(scored.getFraudScore()).isEqualTo(score);
        assertThat(alert).isNotNull();
        assertThat(alert.getSourceEventId()).isEqualTo(eventId);
        assertThat(alert.getFraudScore()).isEqualTo(score);
        assertThat(suspicious).isNotNull();
        assertThat(suspicious.getSourceEventId()).isEqualTo(eventId);
        assertThat(suspicious.getRiskScore()).isEqualTo(score);
        assertThat(fraudCase).isNotNull();
        assertThat(fraudCase.getTransactions()).singleElement()
                .satisfies(transaction -> assertThat(transaction.getFraudScore()).isEqualTo(score));
        assertThat(intelligence).isNotNull();
        assertThat(intelligence.getEngines())
                .filteredOn(engine -> "ml.python.primary".equals(engine.engineId()))
                .singleElement()
                .satisfies(engine -> assertThat(engine.modelIdentity().modelVersion()).isEqualTo(modelVersion));
        var feedbackFacingReadModel = engineIntelligenceReadService.read(TRANSACTION_ID);
        assertThat(feedbackFacingReadModel.engines())
                .filteredOn(engine -> "ml.python.primary".equals(engine.engineId()))
                .singleElement()
                .satisfies(engine -> assertThat(engine.modelIdentity().modelVersion()).isEqualTo(modelVersion));
        assertThat(Arrays.stream(feedbackFacingReadModel.getClass().getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("mlScore");
        assertThat(feedbackFacingReadModel.engines()).allSatisfy(engine ->
                assertThat(Arrays.stream(engine.getClass().getRecordComponents())
                        .map(component -> component.getName()))
                        .doesNotContain("mlScore"));
    }

    private void assertCurrentScoredOccurrence(
            String eventId,
            RiskLevel riskLevel,
            boolean alertRecommended
    ) {
        ScoredTransactionDocument current = mongoTemplate.findById(TRANSACTION_ID, ScoredTransactionDocument.class);
        assertThat(current).isNotNull();
        assertThat(current.getSourceEventId()).isEqualTo(eventId);
        assertThat(current.getRiskLevel()).isEqualTo(riskLevel);
        assertThat(current.getAlertRecommended()).isEqualTo(alertRecommended);
    }

    private void assertHistoricalEvidence(TransactionScoredEvent... events) {
        assertThat(evidenceRepository.findAll()).hasSize(events.length);
        for (TransactionScoredEvent event : events) {
            MlPredictionEvidenceProjection stored = evidenceRepository.findById(event.eventId()).orElseThrow();
            assertThat(stored.getTransactionId()).isEqualTo(event.transactionId());
            assertThat(stored.getMlScore()).isEqualTo(event.mlPredictionEvidence().mlScore());
            assertThat(stored.getModelName()).isEqualTo(event.mlPredictionEvidence().modelName());
            assertThat(stored.getModelVersion()).isEqualTo(event.mlPredictionEvidence().modelVersion());
            assertThat(stored.getFeatureContractVersion())
                    .isEqualTo(event.mlPredictionEvidence().featureContractVersion());
            assertThat(stored.getSourceExecutionTimestamp())
                    .isEqualTo(event.mlPredictionEvidence().sourceExecutionTimestamp());
        }
    }

    private void assertExactlyOneBusinessEffect() {
        assertThat(mongoTemplate.count(new Query(), ScoredTransactionDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), EngineIntelligenceProjection.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), SuspiciousTransactionDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), FraudCaseDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), FraudAlertOutboxRecord.class)).isEqualTo(1L);
    }

    private void createCurrentProjectionIndexes() {
        mongoTemplate.getCollection("alerts").createIndex(
                Indexes.ascending("transactionId"),
                new IndexOptions().unique(true)
        );
        mongoTemplate.getCollection("suspicious_transactions").createIndex(
                Indexes.ascending("transactionId"),
                new IndexOptions().unique(true)
        );
        mongoTemplate.getCollection("fraud_cases").createIndex(
                Indexes.ascending("caseKey"),
                new IndexOptions().unique(true)
        );
        mongoTemplate.getCollection("fraud_alert_outbox_records").createIndex(
                Indexes.ascending("alertId"),
                new IndexOptions().unique(true)
        );
    }

    private TransactionScoredEvent event(String eventId, Instant createdAt, double score, String modelVersion) {
        return event(eventId, createdAt, score, modelVersion, engineIntelligence(createdAt, modelVersion, score));
    }

    private TransactionScoredEvent event(
            String eventId,
            Instant createdAt,
            double score,
            String modelVersion,
            EngineIntelligenceSummary engineIntelligence
    ) {
        return event(eventId, createdAt, score, modelVersion, RiskLevel.HIGH, true, engineIntelligence);
    }

    private TransactionScoredEvent event(
            String eventId,
            Instant createdAt,
            double score,
            String modelVersion,
            RiskLevel riskLevel,
            boolean alertRecommended,
            EngineIntelligenceSummary engineIntelligence
    ) {
        TransactionScoredEvent defaults = TransactionFixtures.scoredTransaction().build();
        return new TransactionScoredEvent(
                eventId,
                TRANSACTION_ID,
                "correlation-" + eventId,
                "customer-occurrence-1",
                "account-occurrence-1",
                createdAt,
                defaults.transactionTimestamp(),
                defaults.transactionAmount(),
                defaults.merchantInfo(),
                defaults.deviceInfo(),
                defaults.locationInfo(),
                defaults.customerContext(),
                score,
                riskLevel,
                "COMPARE",
                "rules-v2-with-ml-diagnostic",
                modelVersion,
                createdAt,
                riskLevel == RiskLevel.HIGH ? List.of("HIGH_VELOCITY") : List.of(),
                Map.of("scoreDecisionId", "decision-" + eventId),
                rapidTransferSnapshot(),
                alertRecommended,
                List.of(),
                engineIntelligence,
                engineIntelligence == null
                        ? null
                        : new MlPredictionEvidenceV1(
                                score,
                                riskLevel,
                                modelIdentity(modelVersion),
                                createdAt.minusNanos(123_456_789L)
                        ),
                engineIntelligence == null
                        ? MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
                        : null,
                null
        );
    }

    private Map<String, Object> rapidTransferSnapshot() {
        return Map.of(
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("transfer-previous", TRANSACTION_ID),
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("20000.00")
        );
    }

    private EngineIntelligenceSummary engineIntelligence(Instant generatedAt, String modelVersion, double score) {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                generatedAt,
                List.of(
                        new EngineIntelligenceEngineResult(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.from(FraudEngineStatus.AVAILABLE, score),
                                List.of("MODEL_HIGH_RISK"),
                                modelIdentity(modelVersion)
                        )
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        List.of("rules.primary", "ml.python.primary"),
                        EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.SMALL
                ),
                List.of(),
                List.of()
        );
    }

    private MlModelIdentity modelIdentity(String modelVersion) {
        return new MlModelIdentity(
                "python-logistic-fraud-model",
                modelVersion,
                "2026-05-30.feature-contract.v1"
        );
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TEST_COORDINATION_INTERRUPTED", exception);
        }
    }
}
