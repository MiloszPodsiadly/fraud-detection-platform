package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.messaging.MlPredictionEvidenceEventListener;
import com.frauddetection.alert.messaging.MlPredictionEvidenceTransientProcessingException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@Testcontainers
class MlPredictionEvidenceProjectionMongoIntegrationTest {

    private static final Instant PROJECTED_AT = Instant.parse("2026-10-03T10:16:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private MlPredictionEvidenceProjectionRepository repository;
    private MlPredictionEvidenceProjectionService service;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "ml_prediction_evidence_test");
        mongoTemplate.dropCollection(MlPredictionEvidenceProjection.class);
        repository = new MongoRepositoryFactory(mongoTemplate)
                .getRepository(MlPredictionEvidenceProjectionRepository.class);
        service = new MlPredictionEvidenceProjectionService(
                repository,
                new EngineIntelligenceProjectionPolicy(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                Clock.fixed(PROJECTED_AT, ZoneOffset.UTC)
        );
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void successfulProjectionPreservesExactEvidenceAndSourceIdentity() {
        var event = MlPredictionEvidenceProjectionTestSupport.event("evt-success", 0.8123d, "model-v1");

        MlPredictionEvidenceProjectionResult result = service.project(event);
        MlPredictionEvidenceProjection stored = repository.findById("evt-success").orElseThrow();

        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.PROJECTED);
        assertThat(stored.getTransactionId()).isEqualTo(event.transactionId());
        assertThat(stored.getCorrelationId()).isEqualTo(event.correlationId());
        assertThat(stored.getSourceEventCreatedAt()).isEqualTo(event.createdAt());
        assertThat(stored.getMlScore()).isEqualTo(0.8123d);
        assertThat(stored.getModelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(stored.getModelVersion()).isEqualTo("model-v1");
        assertThat(stored.getFeatureContractVersion()).isEqualTo("2026-05-30.feature-contract.v1");
        assertThat(stored.getSourceExecutionTimestamp())
                .isEqualTo(MlPredictionEvidenceProjectionTestSupport.EXECUTED_AT);
    }

    @Test
    void identicalKafkaReplayIsIdempotent() {
        var event = MlPredictionEvidenceProjectionTestSupport.event("evt-replay", 0.8123d, "model-v1");

        assertThat(service.project(event).status()).isEqualTo(MlPredictionEvidenceProjectionStatus.PROJECTED);
        assertThat(service.project(event).status()).isEqualTo(MlPredictionEvidenceProjectionStatus.IDEMPOTENT_REPLAY);

        assertThat(repository.count()).isEqualTo(1L);
        assertThat(repository.findById("evt-replay").orElseThrow().getMlScore()).isEqualTo(0.8123d);
    }

    @Test
    void replayWithoutEvidenceCannotEraseAcceptedEvidenceForSameOccurrence() {
        service.project(MlPredictionEvidenceProjectionTestSupport.event(
                "evt-absence-replay",
                0.8123d,
                "model-v1"
        ));

        MlPredictionEvidenceProjectionResult result = service.project(
                MlPredictionEvidenceProjectionTestSupport.eventWithoutEvidence("evt-absence-replay")
        );

        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.OMITTED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.EVIDENCE_ABSENT);
        assertThat(repository.findById("evt-absence-replay").orElseThrow().getMlScore()).isEqualTo(0.8123d);
        assertThat(repository.count()).isEqualTo(1L);
    }

    @Test
    void conflictingKafkaReplayCannotOverwriteAcceptedScoreOrSourceIdentity() {
        var original = MlPredictionEvidenceProjectionTestSupport.event("evt-conflict", 0.8123d, "model-v1");
        var changedScore = MlPredictionEvidenceProjectionTestSupport.event("evt-conflict", 0.7123d, "model-v1");
        var changedSource = MlPredictionEvidenceProjectionTestSupport.event(
                "evt-conflict",
                "txn-other",
                "corr-other",
                0.8123d,
                "model-v1",
                MlPredictionEvidenceProjectionTestSupport.EVENT_CREATED_AT
        );

        service.project(original);

        assertConflict(service.project(changedScore));
        assertConflict(service.project(changedSource));
        MlPredictionEvidenceProjection stored = repository.findById("evt-conflict").orElseThrow();
        assertThat(stored.getMlScore()).isEqualTo(0.8123d);
        assertThat(stored.getTransactionId()).isEqualTo("txn-evidence-1");
        assertThat(stored.getCorrelationId()).isEqualTo("corr-evidence-1");
    }

    @Test
    void differentModelVersionOnReplayCannotOverwriteAcceptedEvidence() {
        service.project(MlPredictionEvidenceProjectionTestSupport.event("evt-model", 0.8123d, "model-v1"));

        MlPredictionEvidenceProjectionResult result = service.project(
                MlPredictionEvidenceProjectionTestSupport.event("evt-model", 0.8123d, "model-v2")
        );

        assertConflict(result);
        assertThat(repository.findById("evt-model").orElseThrow().getModelVersion()).isEqualTo("model-v1");
    }

    @Test
    void distinctSourceEventIsASeparateLegitimateScoringOccurrence() {
        service.project(MlPredictionEvidenceProjectionTestSupport.event("evt-occurrence-1", 0.8123d, "model-v1"));
        service.project(MlPredictionEvidenceProjectionTestSupport.event("evt-occurrence-2", 0.7123d, "model-v2"));

        assertThat(repository.count()).isEqualTo(2L);
    }

    @Test
    void concurrentDuplicateWritesCreateOneImmutableProjection() throws Exception {
        var event = MlPredictionEvidenceProjectionTestSupport.event("evt-concurrent", 0.8123d, "model-v1");
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<MlPredictionEvidenceProjectionResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.project(event);
                }));
            }
            ready.await();
            start.countDown();

            List<MlPredictionEvidenceProjectionStatus> statuses = new ArrayList<>();
            for (Future<MlPredictionEvidenceProjectionResult> future : futures) {
                statuses.add(future.get().status());
            }

            assertThat(statuses).containsOnly(
                    MlPredictionEvidenceProjectionStatus.PROJECTED,
                    MlPredictionEvidenceProjectionStatus.IDEMPOTENT_REPLAY
            );
            assertThat(statuses).containsExactlyInAnyOrderElementsOf(expectedConcurrentStatuses(workers));
            assertThat(repository.count()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void transientEvidenceFailureSurvivesConsumerRestartAndRecoversExactOccurrence() {
        var event = MlPredictionEvidenceProjectionTestSupport.event(
                "evt-durable-recovery",
                0.8123d,
                "model-v1"
        );
        MlPredictionEvidenceProjectionRepository faultInjectedRepository = mock(
                MlPredictionEvidenceProjectionRepository.class,
                delegatesTo(repository)
        );
        doThrow(new DataAccessResourceFailureException("simulated evidence store interruption"))
                .doAnswer(invocation -> repository.insert(
                        (MlPredictionEvidenceProjection) invocation.getArgument(0)
                ))
                .when(faultInjectedRepository)
                .insert((MlPredictionEvidenceProjection) any(MlPredictionEvidenceProjection.class));

        MlPredictionEvidenceEventListener interruptedConsumer = new MlPredictionEvidenceEventListener(
                evidenceService(faultInjectedRepository)
        );

        assertThatThrownBy(() -> interruptedConsumer.onMessage(event))
                .isInstanceOf(MlPredictionEvidenceTransientProcessingException.class)
                .hasMessage("ML_PREDICTION_EVIDENCE_PROJECTION_STORE_UNAVAILABLE");
        assertThat(repository.count()).isZero();

        MlPredictionEvidenceEventListener restartedConsumer = new MlPredictionEvidenceEventListener(
                evidenceService(faultInjectedRepository)
        );
        assertThatCode(() -> restartedConsumer.onMessage(event)).doesNotThrowAnyException();
        assertThatCode(() -> restartedConsumer.onMessage(event)).doesNotThrowAnyException();

        MlPredictionEvidenceProjection stored = repository.findById(event.eventId()).orElseThrow();
        assertThat(repository.count()).isEqualTo(1L);
        assertThat(stored.getSourceEventId()).isEqualTo(event.eventId());
        assertThat(stored.getTransactionId()).isEqualTo(event.transactionId());
        assertThat(stored.getCorrelationId()).isEqualTo(event.correlationId());
        assertThat(stored.getMlScore()).isEqualTo(event.mlPredictionEvidence().mlScore());
        assertThat(stored.getModelName()).isEqualTo(event.mlPredictionEvidence().modelName());
        assertThat(stored.getModelVersion()).isEqualTo(event.mlPredictionEvidence().modelVersion());
        assertThat(stored.getFeatureContractVersion())
                .isEqualTo(event.mlPredictionEvidence().featureContractVersion());
    }

    @Test
    void invalidStoredShapeFailsClosedDuringReplayClassification() {
        var event = MlPredictionEvidenceProjectionTestSupport.event("evt-corrupt", 0.8123d, "model-v1");
        Document corrupt = new Document("_id", "evt-corrupt")
                .append("transactionId", event.transactionId())
                .append("correlationId", event.correlationId())
                .append("sourceEventCreatedAt", event.createdAt().toString())
                .append("contractVersion", 1)
                .append("sourceEngineId", "ml.python.primary")
                .append("engineStatus", "AVAILABLE")
                .append("mlScore", 1.5d)
                .append("mlRiskLevel", "HIGH")
                .append("modelName", "python-logistic-fraud-model")
                .append("modelVersion", "model-v1")
                .append("featureContractVersion", "2026-05-30.feature-contract.v1")
                .append("sourceExecutionTimestamp", MlPredictionEvidenceProjectionTestSupport.EXECUTED_AT.toString())
                .append("projectedAt", PROJECTED_AT);
        mongoTemplate.getCollection("ml_prediction_evidence_projections").insertOne(corrupt);

        MlPredictionEvidenceProjectionResult result = service.project(event);

        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.FAILED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.INVALID_STORED_SHAPE);
        assertThat(mongoTemplate.getCollection("ml_prediction_evidence_projections").countDocuments()).isEqualTo(1L);
    }

    private void assertConflict(MlPredictionEvidenceProjectionResult result) {
        assertThat(result.status()).isEqualTo(MlPredictionEvidenceProjectionStatus.FAILED);
        assertThat(result.reason()).contains(MlPredictionEvidenceProjectionReason.REPLAY_CONFLICT);
    }

    private MlPredictionEvidenceProjectionService evidenceService(
            MlPredictionEvidenceProjectionRepository projectionRepository
    ) {
        return new MlPredictionEvidenceProjectionService(
                projectionRepository,
                new EngineIntelligenceProjectionPolicy(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                Clock.fixed(PROJECTED_AT, ZoneOffset.UTC)
        );
    }

    private List<MlPredictionEvidenceProjectionStatus> expectedConcurrentStatuses(int workers) {
        List<MlPredictionEvidenceProjectionStatus> statuses = new ArrayList<>();
        statuses.add(MlPredictionEvidenceProjectionStatus.PROJECTED);
        for (int index = 1; index < workers; index++) {
            statuses.add(MlPredictionEvidenceProjectionStatus.IDEMPOTENT_REPLAY);
        }
        return statuses;
    }
}
