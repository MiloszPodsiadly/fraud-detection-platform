package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionRepository;
import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import com.frauddetection.alert.feedback.FraudFeedbackRepository;
import com.frauddetection.alert.feedback.governance.FeedbackDatasetEligibilityPolicy;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FeedbackDatasetPopulationMongoIntegrationTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant BUILT_AT = Instant.parse("2026-10-02T12:00:00Z");
    private static final String MODEL_NAME = "python-logistic-fraud-model";
    private static final String MODEL_VERSION = "2026-10-01.v1";
    private static final String FEATURE_CONTRACT = "feature-contract-v2";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private FraudFeedbackRepository feedbackRepository;
    private MlPredictionEvidenceProjectionRepository evidenceRepository;
    private FeedbackDatasetBuilder builder;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        String database = "feedback_population_" + UUID.randomUUID().toString().substring(0, 8);
        mongoTemplate = new MongoTemplate(mongoClient, database);
        MongoRepositoryFactory repositories = new MongoRepositoryFactory(mongoTemplate);
        feedbackRepository = repositories.getRepository(FraudFeedbackRepository.class);
        evidenceRepository = repositories.getRepository(MlPredictionEvidenceProjectionRepository.class);
        builder = new FeedbackDatasetBuilder(
                new FeedbackDatasetCandidateStore(mongoTemplate),
                new FeedbackDatasetMappingPolicy(new FeedbackDatasetEligibilityPolicy()),
                evidenceRepository,
                Clock.fixed(BUILT_AT, ZoneOffset.UTC)
        );
    }

    @AfterEach
    void tearDown() {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    void retainsAndClassifiesCompleteBoundedMlEvidencePopulation() {
        for (int index = 1; index <= 10; index++) {
            FraudFeedbackRecord feedback = feedback(index);
            if (index == 10) {
                feedback.setMlModelName(MODEL_NAME);
            }
            feedbackRepository.save(feedback);
            if (index <= 4) {
                double score = List.of(0.81, 0.82, 0.83, 0.84).get(index - 1);
                evidenceRepository.save(evidence(index, "txn-" + index, MODEL_VERSION, score));
            } else if (index >= 8 && index <= 9) {
                evidenceRepository.save(evidence(index, "different-txn-" + index, MODEL_VERSION, 0.91));
            }
        }

        FeedbackDatasetBuildResult result = builder.build(new FeedbackDatasetBuildRequest(FROM, TO, 10));

        assertThat(result.failed()).isFalse();
        assertThat(result.recordsReturned()).isEqualTo(10);
        Map<FeedbackDatasetMlPredictionEvidenceStatus, Long> counts = result.records().stream()
                .map(FeedbackDatasetRecord::mlPredictionEvidenceStatus)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE, 4L,
                FeedbackDatasetMlPredictionEvidenceStatus.MISSING_UNEXPECTEDLY, 3L,
                FeedbackDatasetMlPredictionEvidenceStatus.IDENTITY_MISMATCH, 2L,
                FeedbackDatasetMlPredictionEvidenceStatus.MALFORMED, 1L
        ));
        assertThat(result.records())
                .filteredOn(record -> record.mlPredictionEvidenceStatus()
                        != FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE)
                .allSatisfy(record -> {
                    assertThat(record.mlPredictionScore()).isNull();
                    assertThat(record.mlPredictionRiskLevel()).isNull();
                    assertThat(record.mlPredictionExecutedAt()).isNull();
                    assertThat(record.mlModelName()).isNull();
                    assertThat(record.mlModelVersion()).isNull();
                    assertThat(record.mlFeatureContractVersion()).isNull();
                });
        assertThat(result.records())
                .filteredOn(record -> record.mlPredictionEvidenceStatus()
                        == FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE)
                .allSatisfy(record -> {
                    assertThat(record.mlModelName()).isEqualTo(MODEL_NAME);
                    assertThat(record.mlModelVersion()).isEqualTo(MODEL_VERSION);
                    assertThat(record.mlFeatureContractVersion()).isEqualTo(FEATURE_CONTRACT);
                    assertThat(record.mlModelArtifactSha256()).isEqualTo("a".repeat(64));
                });
    }

    private FraudFeedbackRecord feedback(int index) {
        Instant occurrenceCreatedAt = FROM.plusSeconds(index);
        FraudFeedbackRecord record = new FraudFeedbackRecord();
        record.setFeedbackId("feedback-" + index);
        record.setTransactionId("txn-" + index);
        record.setCorrelationId("correlation-" + index);
        record.setFeedbackLabel(FraudFeedbackLabel.CONFIRMED_FRAUD);
        record.setCreatedAt(FROM.plusSeconds(100 + index));
        record.setDecisionReasonCodes(List.of("ANALYST_CONFIRMED_FRAUD"));
        record.setFraudScore(0.91);
        record.setRiskLevel(RiskLevel.HIGH);
        ReflectionTestUtils.setField(record, "sourceEventId", "event-" + index);
        ReflectionTestUtils.setField(record, "sourceEventCreatedAt", occurrenceCreatedAt.toString());
        ReflectionTestUtils.setField(record, "sourceEventCreatedAtEpochSecond", occurrenceCreatedAt.getEpochSecond());
        ReflectionTestUtils.setField(record, "sourceEventCreatedAtNano", occurrenceCreatedAt.getNano());
        ReflectionTestUtils.setField(record, "sourceEventFingerprint", "a".repeat(64));
        return record;
    }

    private MlPredictionEvidenceProjection evidence(
            int index,
            String transactionId,
            String modelVersion,
            double score
    ) {
        return new MlPredictionEvidenceProjection(
                "event-" + index,
                transactionId,
                "correlation-" + index,
                FROM.plusSeconds(index).toString(),
                score,
                RiskLevel.HIGH,
                MODEL_NAME,
                modelVersion,
                FEATURE_CONTRACT,
                "a".repeat(64),
                FROM.plusSeconds(50 + index).toString(),
                BUILT_AT
        );
    }
}
