package com.frauddetection.alert.feedback;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxService;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModelMapper;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadService;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.MlModelIdentity;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class FraudFeedbackOccurrenceSnapshotMongoIntegrationTest {

    private static final String TRANSACTION_ID = "transaction-feedback-lineage";
    private static final String EVENT_A = "event-feedback-a";
    private static final String EVENT_B = "event-feedback-b";
    private static final String FINGERPRINT_A = "a".repeat(64);
    private static final String FINGERPRINT_B = "b".repeat(64);
    private static final Instant CREATED_A = Instant.parse("2026-10-05T10:00:00.000000100Z");
    private static final Instant CREATED_B = Instant.parse("2026-10-05T10:00:00.000000200Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private TransactionTemplate transactionTemplate;
    private ScoredTransactionRepository scoredTransactions;
    private EngineIntelligenceProjectionRepository engineIntelligence;
    private MlPredictionEvidenceProjectionRepository evidence;
    private FraudFeedbackRepository feedback;

    @BeforeEach
    void setUp() {
        String databaseName = "feedback_lineage_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(MONGO.getReplicaSetUrl(databaseName));
        mongoTemplate = new MongoTemplate(databaseFactory);
        transactionTemplate = new TransactionTemplate(new MongoTransactionManager(databaseFactory));
        MongoRepositoryFactory repositories = new MongoRepositoryFactory(mongoTemplate);
        scoredTransactions = repositories.getRepository(ScoredTransactionRepository.class);
        engineIntelligence = repositories.getRepository(EngineIntelligenceProjectionRepository.class);
        evidence = repositories.getRepository(MlPredictionEvidenceProjectionRepository.class);
        feedback = repositories.getRepository(FraudFeedbackRepository.class);
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
    void feedbackKeepsOneAuthoritativeOccurrenceAcrossConcurrentRescore() throws Exception {
        scoredTransactions.save(scoredTransaction(EVENT_A, CREATED_A, FINGERPRINT_A, 0.81d));
        engineIntelligence.save(projection(EVENT_A, CREATED_A, FINGERPRINT_A, "model-a"));
        evidence.save(evidence(EVENT_A, CREATED_A, "model-a"));
        evidence.save(evidence(EVENT_B, CREATED_B, "model-b"));

        CountDownLatch occurrenceRead = new CountDownLatch(1);
        CountDownLatch allowFeedbackSnapshot = new CountDownLatch(1);
        FraudFeedbackService service = service(occurrenceRead, allowFeedbackSnapshot);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<FraudFeedbackResponse> created = executor.submit(() -> service.create(TRANSACTION_ID, request()));
            assertThat(occurrenceRead.await(10, TimeUnit.SECONDS)).isTrue();

            scoredTransactions.save(scoredTransaction(EVENT_B, CREATED_B, FINGERPRINT_B, 0.97d));
            engineIntelligence.save(projection(EVENT_B, CREATED_B, FINGERPRINT_B, "model-b"));
            allowFeedbackSnapshot.countDown();

            assertThat(created.get(10, TimeUnit.SECONDS).transactionId()).isEqualTo(TRANSACTION_ID);
        }

        FraudFeedbackRecord saved = feedback.findByTransactionId(TRANSACTION_ID).orElseThrow();
        assertThat(saved.getSourceEventId()).isEqualTo(EVENT_A);
        assertThat(saved.getSourceEventCreatedAt()).isEqualTo(CREATED_A);
        assertThat(saved.getSourceEventCreatedAtText()).isEqualTo(CREATED_A.toString());
        assertThat(saved.getSourceEventCreatedAtEpochSecond()).isEqualTo(CREATED_A.getEpochSecond());
        assertThat(saved.getSourceEventCreatedAtNano()).isEqualTo(CREATED_A.getNano());
        assertThat(saved.getSourceEventFingerprint()).isEqualTo(FINGERPRINT_A);
        assertThat(saved.getFraudScore()).isEqualTo(0.81d);
        assertThat(saved.getMlModelVersion()).isEqualTo("model-a");
        assertThat(scoredTransactions.findById(TRANSACTION_ID).orElseThrow().getSourceEventId()).isEqualTo(EVENT_B);
        assertThat(engineIntelligence.findById(TRANSACTION_ID).orElseThrow().getSourceEventId()).isEqualTo(EVENT_B);
        assertThat(evidence.findById(saved.getSourceEventId()).orElseThrow().getModelVersion()).isEqualTo("model-a");
        assertThat(evidence.findById(saved.getSourceEventId()).orElseThrow().getModelVersion()).isNotEqualTo("model-b");
    }

    @Test
    void historicalIdentityFreeFeedbackRemainsLineageUnavailableWithoutGuessing() {
        mongoTemplate.getCollection("fraud_feedback_records").insertOne(new Document()
                .append("_id", "feedback-historical")
                .append("transactionId", TRANSACTION_ID));

        FraudFeedbackRecord historical = feedback.findById("feedback-historical").orElseThrow();

        assertThat(historical.scoringOccurrenceOwnership()).isEmpty();
    }

    @Test
    void partialHistoricalOccurrenceIdentityFailsClosed() {
        mongoTemplate.getCollection("fraud_feedback_records").insertOne(new Document()
                .append("_id", "feedback-partial")
                .append("transactionId", TRANSACTION_ID)
                .append("sourceEventId", EVENT_A));

        FraudFeedbackRecord partial = feedback.findById("feedback-partial").orElseThrow();

        assertThatThrownBy(partial::scoringOccurrenceOwnership)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FRAUD_FEEDBACK_SCORING_OCCURRENCE_IDENTITY_INVALID");
    }

    @Test
    void auditIntentFailureRollsBackFeedbackInRequiredMongoTransaction() {
        scoredTransactions.save(scoredTransaction(EVENT_A, CREATED_A, FINGERPRINT_A, 0.81d));
        engineIntelligence.save(projection(EVENT_A, CREATED_A, FINGERPRINT_A, "model-a"));
        CountDownLatch occurrenceRead = new CountDownLatch(1);
        CountDownLatch allowFeedbackSnapshot = new CountDownLatch(0);
        WriteActionAuditOutboxService auditOutbox = mock(WriteActionAuditOutboxService.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditOutbox)
                .createPendingAudit(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()
                );

        assertThatThrownBy(() -> service(occurrenceRead, allowFeedbackSnapshot, auditOutbox)
                .create(TRANSACTION_ID, request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(occurrenceRead.getCount()).isZero();
        assertThat(feedback.findByTransactionId(TRANSACTION_ID)).isEmpty();
    }

    private FraudFeedbackService service(
            CountDownLatch occurrenceRead,
            CountDownLatch allowFeedbackSnapshot
    ) {
        return service(
                occurrenceRead,
                allowFeedbackSnapshot,
                mock(WriteActionAuditOutboxService.class)
        );
    }

    private FraudFeedbackService service(
            CountDownLatch occurrenceRead,
            CountDownLatch allowFeedbackSnapshot,
            WriteActionAuditOutboxService auditOutbox
    ) {
        ScoredTransactionDocumentMapper scoredTransactionMapper = new ScoredTransactionDocumentMapper();
        TransactionMonitoringUseCase monitoring = mock(TransactionMonitoringUseCase.class);
        when(monitoring.getScoredTransaction(TRANSACTION_ID)).thenAnswer(invocation -> {
            ScoredTransaction snapshot = scoredTransactionMapper.toDomain(
                    scoredTransactions.findById(TRANSACTION_ID).orElseThrow()
            );
            occurrenceRead.countDown();
            if (!allowFeedbackSnapshot.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent rescore");
            }
            return snapshot;
        });

        RegulatedMutationTransactionRunner transactionRunner = mock(RegulatedMutationTransactionRunner.class);
        when(transactionRunner.runLocalCommit(any())).thenAnswer(invocation -> transactionTemplate.execute(
                status -> invocation.<Supplier<?>>getArgument(0).get()
        ));
        when(transactionRunner.mode()).thenReturn(RegulatedMutationTransactionMode.REQUIRED);

        CurrentAnalystUser currentUser = mock(CurrentAnalystUser.class);
        when(currentUser.get()).thenReturn(Optional.of(new AnalystPrincipal("analyst-1", Set.of(), Set.of())));

        return new FraudFeedbackService(
                feedback,
                new FraudFeedbackMapper(),
                monitoring,
                new EngineIntelligenceReadService(
                        scoredTransactions,
                        engineIntelligence,
                        new EngineIntelligenceReadModelMapper()
                ),
                new EngineIntelligenceResponseMapper(),
                evidence,
                currentUser,
                auditOutbox,
                transactionRunner,
                Clock.fixed(CREATED_A.plusSeconds(10), ZoneOffset.UTC)
        );
    }

    private ScoredTransactionDocument scoredTransaction(
            String eventId,
            Instant createdAt,
            String fingerprint,
            double score
    ) {
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        document.setTransactionId(TRANSACTION_ID);
        document.setSourceEventId(eventId);
        document.setSourceEventCreatedAt(createdAt.toString());
        document.setSourceEventCreatedAtEpochSecond(createdAt.getEpochSecond());
        document.setSourceEventCreatedAtNano(createdAt.getNano());
        document.setSourceEventFingerprint(fingerprint);
        document.setCustomerId("customer-1");
        document.setCorrelationId("correlation-1");
        document.setTransactionTimestamp(createdAt.minusSeconds(1));
        document.setScoredAt(createdAt);
        document.setFraudScore(score);
        document.setRiskLevel(RiskLevel.HIGH);
        document.setAlertRecommended(true);
        document.setReasonCodes(List.of("MODEL_HIGH_RISK"));
        document.setAnalystRecommendation(AnalystRecommendationResult.absent());
        return document;
    }

    private EngineIntelligenceProjection projection(
            String eventId,
            Instant createdAt,
            String fingerprint,
            String modelVersion
    ) {
        return new EngineIntelligenceProjection(
                TRANSACTION_ID,
                new ScoringOccurrenceOwnership(eventId, createdAt, fingerprint),
                1,
                createdAt,
                EngineIntelligenceComparisonType.RULES_VS_ML,
                List.of("rules.primary", "ml.python.primary"),
                EngineIntelligenceAgreementStatus.AGREEMENT,
                EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                EngineIntelligenceScoreDeltaBucket.SMALL,
                List.of(
                        new EngineIntelligenceEngineProjection(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        new EngineIntelligenceEngineProjection(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("MODEL_HIGH_RISK"),
                                new MlModelIdentity("fraud-model", modelVersion, "feature-contract-v2")
                        )
                ),
                List.of(),
                List.of(),
                createdAt,
                createdAt
        );
    }

    private MlPredictionEvidenceProjection evidence(String eventId, Instant createdAt, String modelVersion) {
        return new MlPredictionEvidenceProjection(
                eventId,
                TRANSACTION_ID,
                "correlation-1",
                createdAt.toString(),
                0.91d,
                RiskLevel.HIGH,
                "fraud-model",
                modelVersion,
                "feature-contract-v2",
                "a".repeat(64),
                createdAt.toString(),
                createdAt
        );
    }

    private CreateFraudFeedbackRequest request() {
        return new CreateFraudFeedbackRequest(
                AnalystDecision.MARKED_FRAUD,
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                List.of("ANALYST_CONFIRMED_FRAUD"),
                null
        );
    }
}
