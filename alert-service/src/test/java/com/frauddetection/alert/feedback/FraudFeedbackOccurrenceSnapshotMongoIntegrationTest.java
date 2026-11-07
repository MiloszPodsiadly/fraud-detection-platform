package com.frauddetection.alert.feedback;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.domain.ScoringOccurrenceToken;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxService;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRecord;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRepository;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModelMapper;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadService;
import com.frauddetection.alert.feedback.assembly.FraudFeedbackRecordAssembler;
import com.frauddetection.alert.feedback.snapshot.EngineIntelligenceFeedbackSnapshotter;
import com.frauddetection.alert.feedback.snapshot.MlPredictionEvidenceSnapshotter;
import com.frauddetection.alert.feedback.validation.FraudFeedbackRequestValidator;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionProjectionWriter;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.persistence.FeedbackOccurrenceFenceException;
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
import com.mongodb.MongoException;
import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEWER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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
    private static final int KAFKA_RETRY_ATTEMPTS = 3;
    private static final long KAFKA_RETRY_BACKOFF_MILLIS = 1_000L;

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private TransactionTemplate transactionTemplate;
    private ScoredTransactionRepository scoredTransactions;
    private EngineIntelligenceProjectionRepository engineIntelligence;
    private MlPredictionEvidenceProjectionRepository evidence;
    private FraudFeedbackRepository feedback;
    private ScoredTransactionProjectionWriter projectionWriter;
    private WriteActionAuditOutboxRepository writeActionAuditOutbox;

    @BeforeEach
    void setUp() {
        String databaseName = "feedback_lineage_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(MONGO.getReplicaSetUrl(databaseName));
        mongoTemplate = spy(new MongoTemplate(databaseFactory));
        transactionTemplate = new TransactionTemplate(new MongoTransactionManager(databaseFactory));
        MongoRepositoryFactory repositories = new MongoRepositoryFactory(mongoTemplate);
        scoredTransactions = repositories.getRepository(ScoredTransactionRepository.class);
        engineIntelligence = repositories.getRepository(EngineIntelligenceProjectionRepository.class);
        evidence = repositories.getRepository(MlPredictionEvidenceProjectionRepository.class);
        feedback = repositories.getRepository(FraudFeedbackRepository.class);
        writeActionAuditOutbox = repositories.getRepository(WriteActionAuditOutboxRepository.class);
        projectionWriter = new ScoredTransactionProjectionWriter(mongoTemplate);
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
    void concurrentNewOccurrenceWinsBeforeFeedbackFenceAndStaleFeedbackIsRejected() throws Exception {
        scoredTransactions.save(scoredTransaction(EVENT_A, CREATED_A, FINGERPRINT_A, 0.81d));
        engineIntelligence.save(projection(EVENT_A, CREATED_A, FINGERPRINT_A, "model-a"));
        evidence.save(evidence(EVENT_A, CREATED_A, "model-a"));
        evidence.save(evidence(EVENT_B, CREATED_B, "model-b"));

        CountDownLatch occurrenceRead = new CountDownLatch(1);
        CountDownLatch allowFeedbackSnapshot = new CountDownLatch(1);
        WriteActionAuditOutboxService auditOutbox = mock(WriteActionAuditOutboxService.class);
        FraudFeedbackService service = service(occurrenceRead, allowFeedbackSnapshot, auditOutbox);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<FraudFeedbackResponse> created = executor.submit(() -> service.create(TRANSACTION_ID, request()));
            assertThat(occurrenceRead.await(10, TimeUnit.SECONDS)).isTrue();

            transactionTemplate.execute(status -> projectionWriter.write(
                    scoredTransaction(EVENT_B, CREATED_B, FINGERPRINT_B, 0.97d)
            ));
            engineIntelligence.save(projection(EVENT_B, CREATED_B, FINGERPRINT_B, "model-b"));
            allowFeedbackSnapshot.countDown();

            assertThatThrownBy(() -> created.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .satisfies(exception -> {
                        Throwable cause = exception.getCause();
                        assertThat(cause).isInstanceOf(ResponseStatusException.class);
                        assertThat(((ResponseStatusException) cause).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(((ResponseStatusException) cause).getReason())
                                .isEqualTo("FRAUD_FEEDBACK_SCORING_OCCURRENCE_CHANGED");
                    });
        }

        assertThat(feedback.findByTransactionId(TRANSACTION_ID)).isEmpty();
        assertThat(scoredTransactions.findById(TRANSACTION_ID).orElseThrow().getSourceEventId()).isEqualTo(EVENT_B);
        assertThat(engineIntelligence.findById(TRANSACTION_ID).orElseThrow().getSourceEventId()).isEqualTo(EVENT_B);
        verify(auditOutbox, never()).createPendingAudit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void feedbackFenceWinsBeforeOverlappingRescoreAndCommitsOneFeedbackAndAudit() throws Exception {
        scoredTransactions.save(scoredTransaction(EVENT_A, CREATED_A, FINGERPRINT_A, 0.81d));
        engineIntelligence.save(projection(EVENT_A, CREATED_A, FINGERPRINT_A, "model-a"));
        evidence.save(evidence(EVENT_A, CREATED_A, "model-a"));

        CountDownLatch feedbackFenceAcquired = new CountDownLatch(1);
        CountDownLatch allowFeedbackCommit = new CountDownLatch(1);
        CountDownLatch rescoreReachedMongoUpdate = new CountDownLatch(1);
        AtomicReference<Thread> rescoreThread = new AtomicReference<>();
        AtomicBoolean rescoreEnteredWhileFeedbackOpen = new AtomicBoolean();
        AtomicInteger rescoreDeliveryAttempts = new AtomicInteger();
        ScoredTransactionProjectionWriter feedbackWriter = spy(projectionWriter);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            feedbackFenceAcquired.countDown();
            if (!allowFeedbackCommit.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release fenced feedback transaction");
            }
            return null;
        }).when(feedbackWriter).fenceFeedbackOccurrence(eq(TRANSACTION_ID), any(ScoringOccurrenceOwnership.class));
        doAnswer(invocation -> {
            if (Thread.currentThread() == rescoreThread.get()) {
                rescoreEnteredWhileFeedbackOpen.set(allowFeedbackCommit.getCount() > 0);
                rescoreReachedMongoUpdate.countDown();
            }
            return invocation.callRealMethod();
        }).when(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(ScoredTransactionDocument.class));

        WriteActionAuditOutboxService auditOutbox = new WriteActionAuditOutboxService(writeActionAuditOutbox);
        FraudFeedbackService feedbackService = service(
                new CountDownLatch(1),
                new CountDownLatch(0),
                auditOutbox,
                "analyst-1",
                feedbackWriter
        );

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<FraudFeedbackResponse> feedbackCommit = executor.submit(
                    () -> feedbackService.create(TRANSACTION_ID, request())
            );
            assertThat(feedbackFenceAcquired.await(10, TimeUnit.SECONDS)).isTrue();

            Future<com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult> rescoreCommit = executor.submit(() -> {
                rescoreThread.set(Thread.currentThread());
                return replayRescoreWithKafkaBoundedRetry(rescoreDeliveryAttempts);
            });
            try {
                assertThat(rescoreReachedMongoUpdate.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(rescoreEnteredWhileFeedbackOpen).isTrue();
            } finally {
                allowFeedbackCommit.countDown();
            }

            FraudFeedbackResponse created = feedbackCommit.get(15, TimeUnit.SECONDS);
            com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult admission =
                    rescoreCommit.get(15, TimeUnit.SECONDS);
            assertThat(created.scoringOccurrenceRelation()).isEqualTo(FraudFeedbackOccurrenceRelation.CURRENT);
            assertThat(admission.outcome()).isEqualTo(APPLIED_NEWER);
        }

        FraudFeedbackRecord saved = feedback.findByTransactionId(TRANSACTION_ID).orElseThrow();
        assertThat(feedback.count()).isEqualTo(1L);
        assertThat(saved.getSourceEventId()).isEqualTo(EVENT_A);
        assertThat(saved.getSourceEventCreatedAt()).isEqualTo(CREATED_A);
        assertThat(saved.getSourceEventFingerprint()).isEqualTo(FINGERPRINT_A);
        assertThat(saved.getMlModelVersion()).isEqualTo("model-a");
        assertThat(scoredTransactions.findById(TRANSACTION_ID).orElseThrow().getSourceEventId()).isEqualTo(EVENT_B);
        assertThat(engineIntelligence.findById(TRANSACTION_ID).orElseThrow().scoringOccurrenceOwnership().sourceEventId()).isEqualTo(EVENT_B);
        assertThat(evidence.findById(EVENT_B).orElseThrow().getSourceEventId()).isEqualTo(EVENT_B);
        assertThat(feedbackService.get(TRANSACTION_ID).scoringOccurrenceRelation()).isEqualTo(FraudFeedbackOccurrenceRelation.PRIOR);
        assertThat(writeActionAuditOutbox.findAll())
                .singleElement()
                .satisfies(audit -> {
                    assertThat(audit.getAction()).isEqualTo(AuditAction.RECORD_FRAUD_FEEDBACK);
                    assertThat(audit.getResourceId()).isEqualTo(saved.getFeedbackId());
                    assertThat(audit.getIdempotencyKey())
                            .isEqualTo("RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:" + saved.getFeedbackId());
                });
        assertThat(rescoreDeliveryAttempts.get()).isBetween(2, KAFKA_RETRY_ATTEMPTS);
    }

    @Test
    void feedbackMayCommitForAThenLaterRescoreMakesItPrior() {
        scoredTransactions.save(scoredTransaction(EVENT_A, CREATED_A, FINGERPRINT_A, 0.81d));
        engineIntelligence.save(projection(EVENT_A, CREATED_A, FINGERPRINT_A, "model-a"));
        evidence.save(evidence(EVENT_A, CREATED_A, "model-a"));
        WriteActionAuditOutboxService auditOutbox = mock(WriteActionAuditOutboxService.class);
        FraudFeedbackService service = service(new CountDownLatch(1), new CountDownLatch(0), auditOutbox);

        FraudFeedbackResponse created = service.create(TRANSACTION_ID, request());
        transactionTemplate.execute(status -> projectionWriter.write(
                scoredTransaction(EVENT_B, CREATED_B, FINGERPRINT_B, 0.97d)
        ));

        assertThat(created.scoringOccurrenceRelation()).isEqualTo(FraudFeedbackOccurrenceRelation.CURRENT);
        assertThat(service.get(TRANSACTION_ID).scoringOccurrenceRelation()).isEqualTo(FraudFeedbackOccurrenceRelation.PRIOR);
        assertThat(feedback.findByTransactionId(TRANSACTION_ID).orElseThrow().getSourceEventId()).isEqualTo(EVENT_A);
        assertThat(scoredTransactions.findById(TRANSACTION_ID).orElseThrow().getSourceEventId()).isEqualTo(EVENT_B);
    }

    @Test
    void secondAnalystCannotReplaceFeedbackForTheSameTransaction() {
        scoredTransactions.save(scoredTransaction(EVENT_A, CREATED_A, FINGERPRINT_A, 0.81d));
        engineIntelligence.save(projection(EVENT_A, CREATED_A, FINGERPRINT_A, "model-a"));
        evidence.save(evidence(EVENT_A, CREATED_A, "model-a"));
        WriteActionAuditOutboxService firstAuditOutbox = mock(WriteActionAuditOutboxService.class);
        WriteActionAuditOutboxService secondAuditOutbox = mock(WriteActionAuditOutboxService.class);
        FraudFeedbackService firstAnalyst = service(
                new CountDownLatch(1),
                new CountDownLatch(0),
                firstAuditOutbox,
                "analyst-1"
        );
        FraudFeedbackService secondAnalyst = service(
                new CountDownLatch(1),
                new CountDownLatch(0),
                secondAuditOutbox,
                "analyst-2"
        );

        FraudFeedbackResponse created = firstAnalyst.create(TRANSACTION_ID, request());
        assertThat(created.createdBy()).isEqualTo("analyst-1");
        assertThatThrownBy(() -> secondAnalyst.create(TRANSACTION_ID, request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException conflict = (ResponseStatusException) exception;
                    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(conflict.getReason()).isEqualTo("FRAUD_FEEDBACK_ALREADY_RECORDED");
                });

        assertThat(feedback.findByTransactionId(TRANSACTION_ID)).isPresent();
        assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), FraudFeedbackRecord.class)).isEqualTo(1L);
        verify(firstAuditOutbox).createPendingAudit(any(), any(), any(), any(), any(), any(), any(), any());
        verify(secondAuditOutbox, never()).createPendingAudit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleReviewedOccurrenceIsRejectedWithoutFeedbackOrSuccessAudit() {
        scoredTransactions.save(scoredTransaction(EVENT_B, CREATED_B, FINGERPRINT_B, 0.97d));
        CountDownLatch occurrenceRead = new CountDownLatch(1);
        WriteActionAuditOutboxService auditOutbox = mock(WriteActionAuditOutboxService.class);

        assertThatThrownBy(() -> service(occurrenceRead, new CountDownLatch(0), auditOutbox)
                .create(TRANSACTION_ID, request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException response = (ResponseStatusException) exception;
                    assertThat(response.getStatusCode().value()).isEqualTo(409);
                    assertThat(response.getReason()).isEqualTo("FRAUD_FEEDBACK_SCORING_OCCURRENCE_CHANGED");
                });

        assertThat(occurrenceRead.getCount()).isZero();
        assertThat(feedback.findByTransactionId(TRANSACTION_ID)).isEmpty();
        assertThat(scoredTransactions.findById(TRANSACTION_ID).orElseThrow().getFeedbackWriteFenceVersion()).isZero();
        verify(auditOutbox, never()).createPendingAudit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void feedbackFenceFailsClosedWithoutAnActiveMongoTransaction() {
        scoredTransactions.save(scoredTransaction(EVENT_A, CREATED_A, FINGERPRINT_A, 0.81d));

        assertThatThrownBy(() -> projectionWriter.fenceFeedbackOccurrence(
                TRANSACTION_ID,
                new ScoringOccurrenceOwnership(EVENT_A, CREATED_A, FINGERPRINT_A)
        ))
                .isInstanceOf(FeedbackOccurrenceFenceException.class)
                .satisfies(exception -> assertThat(((FeedbackOccurrenceFenceException) exception).reason())
                        .isEqualTo(FeedbackOccurrenceFenceException.Reason.TRANSACTION_REQUIRED));

        assertThat(scoredTransactions.findById(TRANSACTION_ID).orElseThrow().getFeedbackWriteFenceVersion()).isZero();
    }

    @Test
    void feedbackFenceReportsTypedStaleOccurrenceInsideTheMongoTransaction() {
        scoredTransactions.save(scoredTransaction(EVENT_B, CREATED_B, FINGERPRINT_B, 0.97d));

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            projectionWriter.fenceFeedbackOccurrence(
                    TRANSACTION_ID,
                    new ScoringOccurrenceOwnership(EVENT_A, CREATED_A, FINGERPRINT_A)
            );
            return null;
        }))
                .isInstanceOf(FeedbackOccurrenceFenceException.class)
                .satisfies(exception -> assertThat(((FeedbackOccurrenceFenceException) exception).reason())
                        .isEqualTo(FeedbackOccurrenceFenceException.Reason.OCCURRENCE_CHANGED));

        assertThat(scoredTransactions.findById(TRANSACTION_ID).orElseThrow().getFeedbackWriteFenceVersion()).isZero();
    }

    @Test
    void feedbackFenceReportsTypedUnavailableForAnUnacknowledgedMongoUpdate() {
        scoredTransactions.save(scoredTransaction(EVENT_A, CREATED_A, FINGERPRINT_A, 0.81d));
        doReturn(UpdateResult.unacknowledged())
                .when(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(ScoredTransactionDocument.class));

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            projectionWriter.fenceFeedbackOccurrence(
                    TRANSACTION_ID,
                    new ScoringOccurrenceOwnership(EVENT_A, CREATED_A, FINGERPRINT_A)
            );
            return null;
        }))
                .isInstanceOf(FeedbackOccurrenceFenceException.class)
                .satisfies(exception -> assertThat(((FeedbackOccurrenceFenceException) exception).reason())
                        .isEqualTo(FeedbackOccurrenceFenceException.Reason.FENCE_UNAVAILABLE));
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
        evidence.save(evidence(EVENT_A, CREATED_A, "model-a"));
        WriteActionAuditOutboxService auditOutbox = spy(new WriteActionAuditOutboxService(writeActionAuditOutbox));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("audit intent failed after persistence");
        }).when(auditOutbox)
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

        assertThatThrownBy(() -> service(new CountDownLatch(1), new CountDownLatch(0), auditOutbox)
                .create(TRANSACTION_ID, request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(feedback.findByTransactionId(TRANSACTION_ID)).isEmpty();
        assertThat(writeActionAuditOutbox.findAll()).isEmpty();
        assertThat(scoredTransactions.findById(TRANSACTION_ID).orElseThrow().getFeedbackWriteFenceVersion()).isZero();
        assertThat(scoredTransactions.findById(TRANSACTION_ID).orElseThrow().getSourceEventId()).isEqualTo(EVENT_A);

        assertThat(transactionTemplate.execute(status -> projectionWriter.write(
                scoredTransaction(EVENT_B, CREATED_B, FINGERPRINT_B, 0.97d)
        )).outcome()).isEqualTo(APPLIED_NEWER);
        assertThat(scoredTransactions.findById(TRANSACTION_ID).orElseThrow().getSourceEventId()).isEqualTo(EVENT_B);
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
        return service(occurrenceRead, allowFeedbackSnapshot, auditOutbox, "analyst-1");
    }

    private FraudFeedbackService service(
            CountDownLatch occurrenceRead,
            CountDownLatch allowFeedbackSnapshot,
            WriteActionAuditOutboxService auditOutbox,
            String actor
    ) {
        return service(occurrenceRead, allowFeedbackSnapshot, auditOutbox, actor, projectionWriter);
    }

    private FraudFeedbackService service(
            CountDownLatch occurrenceRead,
            CountDownLatch allowFeedbackSnapshot,
            WriteActionAuditOutboxService auditOutbox,
            String actor,
            ScoredTransactionProjectionWriter writer
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
        when(transactionRunner.isTransactionManagerConfigured()).thenReturn(true);

        CurrentAnalystUser currentUser = mock(CurrentAnalystUser.class);
        when(currentUser.get()).thenReturn(Optional.of(new AnalystPrincipal(actor, Set.of(), Set.of())));

        EngineIntelligenceReadService engineIntelligenceReadService = new EngineIntelligenceReadService(
                scoredTransactions,
                engineIntelligence,
                new EngineIntelligenceReadModelMapper()
        );
        EngineIntelligenceFeedbackSnapshotter engineIntelligenceSnapshotter =
                new EngineIntelligenceFeedbackSnapshotter(
                        engineIntelligenceReadService,
                        new EngineIntelligenceResponseMapper()
                );
        MlPredictionEvidenceSnapshotter mlPredictionEvidenceSnapshotter =
                new MlPredictionEvidenceSnapshotter(evidence);
        FraudFeedbackRecordAssembler recordAssembler = new FraudFeedbackRecordAssembler();
        return new FraudFeedbackService(
                feedback,
                new FraudFeedbackMapper(),
                monitoring,
                currentUser,
                auditOutbox,
                transactionRunner,
                writer,
                new FraudFeedbackRequestValidator(),
                recordAssembler,
                engineIntelligenceSnapshotter,
                mlPredictionEvidenceSnapshotter,
                Clock.fixed(CREATED_A.plusSeconds(10), ZoneOffset.UTC)
        );
    }

    private com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult replayRescoreWithKafkaBoundedRetry(
            AtomicInteger deliveryAttempts
    ) {
        for (int attempt = 0; attempt < KAFKA_RETRY_ATTEMPTS; attempt++) {
            deliveryAttempts.incrementAndGet();
            try {
                return transactionTemplate.execute(status -> {
                    var admission = projectionWriter.write(
                            scoredTransaction(EVENT_B, CREATED_B, FINGERPRINT_B, 0.97d)
                    );
                    engineIntelligence.save(projection(EVENT_B, CREATED_B, FINGERPRINT_B, "model-b"));
                    evidence.save(evidence(EVENT_B, CREATED_B, "model-b"));
                    return admission;
                });
            } catch (RuntimeException exception) {
                if (!hasMongoErrorLabel(exception, "TransientTransactionError") || attempt == 2) {
                    throw exception;
                }
                awaitScoringRetryBackoff();
            }
        }
        throw new IllegalStateException("SCORING_EVENT_REPLAY_ATTEMPTS_EXHAUSTED");
    }

    private static boolean hasMongoErrorLabel(Throwable exception, String label) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof MongoException mongoException && mongoException.hasErrorLabel(label)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void awaitScoringRetryBackoff() {
        try {
            Thread.sleep(KAFKA_RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SCORING_EVENT_REPLAY_INTERRUPTED", interrupted);
        }
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
                null,
                ScoringOccurrenceToken.from(new ScoringOccurrenceOwnership(EVENT_A, CREATED_A, FINGERPRINT_A))
        );
    }
}
