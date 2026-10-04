package com.frauddetection.alert.persistence;

import com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEW;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEWER;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.CONFLICT_REJECTED;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.IDEMPOTENT_REPLAY;
import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.STALE_REJECTED;

@Testcontainers
class ScoredTransactionProjectionWriterMongoIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-10-03T12:00:00.123456789Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private ScoredTransactionProjectionWriter writer;
    private ScoredTransactionDocumentMapper mapper;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "scored_transaction_lineage_test");
        mongoTemplate.dropCollection(ScoredTransactionDocument.class);
        writer = new ScoredTransactionProjectionWriter(mongoTemplate);
        mapper = new ScoredTransactionDocumentMapper();
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void oneScoredEventOwnsThePersistedTransactionState() {
        ScoringOccurrenceAdmissionResult result = writer.write(
                mapper.toDocument(event("txn-one", "event-one", BASE_TIME, 0.81d, true, "model-v1"))
        );

        ScoredTransactionDocument stored = stored("txn-one");

        assertThat(result.outcome()).isEqualTo(APPLIED_NEW);
        assertThat(result.reasonCode()).isEqualTo(
                ScoringOccurrenceAdmissionResult.ReasonCode.FIRST_OCCURRENCE_ACCEPTED
        );
        assertThat(stored.getSourceEventId()).isEqualTo("event-one");
        assertThat(stored.getSourceEventCreatedAt()).isEqualTo(BASE_TIME.toString());
        assertThat(stored.getSourceEventCreatedAtEpochSecond()).isEqualTo(BASE_TIME.getEpochSecond());
        assertThat(stored.getSourceEventCreatedAtNano()).isEqualTo(BASE_TIME.getNano());
        assertThat(stored.getSourceEventFingerprint()).matches("[0-9a-f]{64}");
        assertThat(stored.getFraudScore()).isEqualTo(0.81d);
    }

    @Test
    void identicalOccurrenceReplayRemainsCurrentWithoutMutatingAcceptedState() {
        TransactionScoredEvent event = event("txn-replay", "event-replay", BASE_TIME, 0.21d, false, "model-v1");
        writer.write(mapper.toDocument(event));

        ScoringOccurrenceAdmissionResult replay = writer.write(mapper.toDocument(event));

        assertThat(replay.outcome()).isEqualTo(IDEMPOTENT_REPLAY);
        assertThat(replay.isCurrentOccurrence()).isTrue();
        assertThat(stored("txn-replay").getFraudScore()).isEqualTo(0.21d);
        assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), ScoredTransactionDocument.class))
                .isEqualTo(1L);
    }

    @Test
    void semanticallyIdenticalMapContentProducesAnIdempotentReplay() {
        Map<String, Object> firstDetails = new LinkedHashMap<>();
        firstDetails.put("velocity", 7);
        firstDetails.put("country", "PL");
        Map<String, Object> reorderedDetails = new LinkedHashMap<>();
        reorderedDetails.put("country", "PL");
        reorderedDetails.put("velocity", 7);
        TransactionScoredEvent first = event(
                "txn-canonical-map", "event-one", BASE_TIME, 0.21d, false, "model-v1", firstDetails
        );
        TransactionScoredEvent replay = event(
                "txn-canonical-map", "event-one", BASE_TIME, 0.21d, false, "model-v1", reorderedDetails
        );

        writer.write(mapper.toDocument(first));
        ScoringOccurrenceAdmissionResult result = writer.write(mapper.toDocument(replay));

        assertThat(result.outcome()).isEqualTo(IDEMPOTENT_REPLAY);
    }

    @Test
    void sameEventIdentityWithChangedFraudScoreIsRejectedAsConflict() {
        writer.write(mapper.toDocument(event("txn-score-conflict", "event-one", BASE_TIME, 0.21d, false, "model-v1")));

        ScoringOccurrenceAdmissionResult conflict = writer.write(mapper.toDocument(event(
                "txn-score-conflict",
                "event-one",
                BASE_TIME,
                0.99d,
                false,
                "model-v1"
        )));

        assertThat(conflict.outcome()).isEqualTo(CONFLICT_REJECTED);
        assertThat(conflict.reasonCode()).isEqualTo(
                ScoringOccurrenceAdmissionResult.ReasonCode.OCCURRENCE_PAYLOAD_CONFLICT
        );
        assertThat(stored("txn-score-conflict").getFraudScore()).isEqualTo(0.21d);
    }

    @Test
    void sameEventIdentityWithChangedAlertRecommendationIsRejectedAsConflict() {
        writer.write(mapper.toDocument(event("txn-alert-conflict", "event-one", BASE_TIME, 0.21d, false, "model-v1")));

        ScoringOccurrenceAdmissionResult conflict = writer.write(mapper.toDocument(event(
                "txn-alert-conflict",
                "event-one",
                BASE_TIME,
                0.21d,
                true,
                "model-v1"
        )));

        assertThat(conflict.outcome()).isEqualTo(CONFLICT_REJECTED);
        assertThat(stored("txn-alert-conflict").getAlertRecommended()).isFalse();
    }

    @Test
    void sameEventIdentityWithChangedSourceMetadataIsRejectedAsConflict() {
        writer.write(mapper.toDocument(event("txn-metadata-conflict", "event-one", BASE_TIME, 0.21d, false, "model-v1")));

        ScoringOccurrenceAdmissionResult conflict = writer.write(mapper.toDocument(event(
                "txn-metadata-conflict",
                "event-one",
                BASE_TIME,
                0.21d,
                false,
                "model-v2"
        )));

        assertThat(conflict.outcome()).isEqualTo(CONFLICT_REJECTED);
        assertThat(stored("txn-metadata-conflict").getFraudScore()).isEqualTo(0.21d);
    }

    @Test
    void changedOccurrenceTimestampForSameEventIdentityIsRejectedAsConflict() {
        writer.write(mapper.toDocument(event("txn-identity-conflict", "event-replay", BASE_TIME, 0.21d, false, "model-v1")));
        ScoringOccurrenceAdmissionResult conflict = writer.write(mapper.toDocument(event(
                "txn-identity-conflict",
                "event-replay",
                BASE_TIME.plusSeconds(30),
                0.99d,
                true,
                "model-conflict"
        )));

        assertThat(conflict.outcome()).isEqualTo(CONFLICT_REJECTED);
    }

    @Test
    void laterLegitimateOccurrenceWinsAcrossModelVersions() {
        writer.write(mapper.toDocument(event("txn-model", "event-model-v1", BASE_TIME, 0.31d, false, "model-v1")));
        ScoringOccurrenceAdmissionResult result = writer.write(mapper.toDocument(event(
                "txn-model",
                "event-model-v2",
                BASE_TIME.plusNanos(1),
                0.82d,
                true,
                "model-v2"
        )));

        ScoredTransactionDocument stored = stored("txn-model");

        assertThat(result.outcome()).isEqualTo(APPLIED_NEWER);
        assertThat(stored.getSourceEventId()).isEqualTo("event-model-v2");
        assertThat(stored.getFraudScore()).isEqualTo(0.82d);
    }

    @Test
    void outOfOrderOlderDeliveryCannotReplaceLaterOccurrence() {
        writer.write(mapper.toDocument(event(
                "txn-out-of-order",
                "event-later",
                BASE_TIME.plusSeconds(1),
                0.88d,
                true,
                "model-v2"
        )));
        ScoringOccurrenceAdmissionResult result = writer.write(mapper.toDocument(event(
                "txn-out-of-order",
                "event-earlier",
                BASE_TIME,
                0.22d,
                false,
                "model-v1"
        )));

        assertThat(result.outcome()).isEqualTo(STALE_REJECTED);
        assertThat(stored("txn-out-of-order").getSourceEventId()).isEqualTo("event-later");
        assertThat(stored("txn-out-of-order").getFraudScore()).isEqualTo(0.88d);
    }

    @Test
    void equalEventTimestampsUseSourceEventIdAsDeterministicTieBreaker() {
        writer.write(mapper.toDocument(event("txn-tie", "event-a", BASE_TIME, 0.11d, false, "model-a")));
        ScoringOccurrenceAdmissionResult winner = writer.write(
                mapper.toDocument(event("txn-tie", "event-z", BASE_TIME, 0.91d, true, "model-z"))
        );
        ScoringOccurrenceAdmissionResult loser = writer.write(
                mapper.toDocument(event("txn-tie", "event-m", BASE_TIME, 0.51d, false, "model-m"))
        );

        assertThat(winner.outcome()).isEqualTo(APPLIED_NEWER);
        assertThat(loser.outcome()).isEqualTo(STALE_REJECTED);
        assertThat(stored("txn-tie").getSourceEventId()).isEqualTo("event-z");
        assertThat(stored("txn-tie").getFraudScore()).isEqualTo(0.91d);
    }

    @Test
    void concurrentWritesConvergeOnTheSameAuthoritativeOccurrence() throws Exception {
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<ScoringOccurrenceAdmissionResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                int occurrence = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return writer.write(mapper.toDocument(event(
                            "txn-concurrent",
                            "event-" + occurrence,
                            BASE_TIME.plusNanos(occurrence),
                            occurrence / 10.0d,
                            occurrence >= 7,
                            "model-" + occurrence
                    )));
                }));
            }
            ready.await();
            start.countDown();
            List<ScoringOccurrenceAdmissionResult> results = new ArrayList<>();
            for (Future<ScoringOccurrenceAdmissionResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results).allSatisfy(result -> assertThat(result.outcome())
                    .isIn(APPLIED_NEW, APPLIED_NEWER, STALE_REJECTED));
            assertThat(stored("txn-concurrent").getSourceEventId()).isEqualTo("event-7");
            assertThat(stored("txn-concurrent").getFraudScore()).isEqualTo(0.7d);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentConflictingReplaysAcceptExactlyOnePayload() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ScoringOccurrenceAdmissionResult> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return writer.write(mapper.toDocument(event(
                        "txn-concurrent-conflict", "event-one", BASE_TIME, 0.21d, false, "model-v1"
                )));
            });
            Future<ScoringOccurrenceAdmissionResult> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return writer.write(mapper.toDocument(event(
                        "txn-concurrent-conflict", "event-one", BASE_TIME, 0.91d, true, "model-v2"
                )));
            });
            ready.await();
            start.countDown();

            assertThat(List.of(first.get().outcome(), second.get().outcome()))
                    .containsExactlyInAnyOrder(APPLIED_NEW, CONFLICT_REJECTED);
            assertThat(stored("txn-concurrent-conflict").getFraudScore()).isIn(0.21d, 0.91d);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void knownOccurrenceAtomicallyReplacesHistoricalUnknownOccurrence() {
        ScoredTransactionDocument historical = new ScoredTransactionDocument();
        historical.setTransactionId("txn-historical");
        historical.setFraudScore(0.12d);
        mongoTemplate.insert(historical);

        ScoringOccurrenceAdmissionResult result = writer.write(mapper.toDocument(event(
                "txn-historical",
                "event-known",
                BASE_TIME,
                0.73d,
                false,
                "model-current"
        )));

        ScoredTransactionDocument stored = stored("txn-historical");
        assertThat(result.outcome()).isEqualTo(APPLIED_NEWER);
        assertThat(result.reasonCode()).isEqualTo(
                ScoringOccurrenceAdmissionResult.ReasonCode.HISTORICAL_OCCURRENCE_CLAIMED
        );
        assertThat(stored.getSourceEventId()).isEqualTo("event-known");
        assertThat(stored.getFraudScore()).isEqualTo(0.73d);
    }

    @Test
    void partiallyCorruptedCurrentOccurrenceCannotBeClaimedOrSilentlyRepaired() {
        ScoredTransactionDocument corrupted = mapper.toDocument(event(
                "txn-corrupted-current",
                "event-corrupted",
                BASE_TIME,
                0.12d,
                false,
                "model-old"
        ));
        corrupted.setSourceEventFingerprint(null);
        mongoTemplate.insert(corrupted);

        ScoredTransactionDocument replacement = mapper.toDocument(event(
                "txn-corrupted-current",
                "event-newer",
                BASE_TIME.plusSeconds(1),
                0.91d,
                true,
                "model-new"
        ));

        assertThatThrownBy(() -> writer.write(replacement))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SCORING_OCCURRENCE_IDENTITY_INVALID");
        assertThat(stored("txn-corrupted-current").getSourceEventId()).isEqualTo("event-corrupted");
        assertThat(stored("txn-corrupted-current").getSourceEventFingerprint()).isNull();
    }

    @Test
    void invalidOccurrenceIdentityIsRejectedBeforePersistence() {
        ScoredTransactionDocument candidate = mapper.toDocument(event(
                "txn-invalid-identity",
                "event-valid",
                BASE_TIME,
                0.42d,
                false,
                "model-current"
        ));
        candidate.setSourceEventId("invalid event id");

        assertThatThrownBy(() -> writer.write(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUTHORITATIVE_SCORING_OCCURRENCE_REQUIRED");
        assertThat(stored("txn-invalid-identity")).isNull();
    }

    private ScoredTransactionDocument stored(String transactionId) {
        return mongoTemplate.findById(transactionId, ScoredTransactionDocument.class);
    }

    private TransactionScoredEvent event(
            String transactionId,
            String eventId,
            Instant createdAt,
            double score,
            boolean alertRecommended,
            String modelVersion
    ) {
        return event(transactionId, eventId, createdAt, score, alertRecommended, modelVersion, Map.of());
    }

    private TransactionScoredEvent event(
            String transactionId,
            String eventId,
            Instant createdAt,
            double score,
            boolean alertRecommended,
            String modelVersion,
            Map<String, Object> scoreDetails
    ) {
        return new TransactionScoredEvent(
                eventId,
                transactionId,
                "correlation-" + transactionId,
                "customer-1",
                "account-1",
                createdAt,
                BASE_TIME.minusSeconds(60),
                null,
                null,
                null,
                null,
                null,
                score,
                score >= 0.8d ? RiskLevel.HIGH : RiskLevel.LOW,
                "RULE_BASED",
                "fraud-model",
                modelVersion,
                createdAt,
                List.of(),
                scoreDetails,
                Map.of(),
                alertRecommended
        );
    }
}
