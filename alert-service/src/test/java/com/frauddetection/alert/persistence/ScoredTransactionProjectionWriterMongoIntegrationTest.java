package com.frauddetection.alert.persistence;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        writer.write(mapper.toDocument(event("txn-one", "event-one", BASE_TIME, 0.81d, "model-v1")));

        ScoredTransactionDocument stored = stored("txn-one");

        assertThat(stored.getSourceEventId()).isEqualTo("event-one");
        assertThat(stored.getSourceEventCreatedAt()).isEqualTo(BASE_TIME.toString());
        assertThat(stored.getSourceEventCreatedAtEpochSecond()).isEqualTo(BASE_TIME.getEpochSecond());
        assertThat(stored.getSourceEventCreatedAtNano()).isEqualTo(BASE_TIME.getNano());
        assertThat(stored.getFraudScore()).isEqualTo(0.81d);
    }

    @Test
    void identicalOccurrenceReplayCannotMutateAcceptedState() {
        writer.write(mapper.toDocument(event("txn-replay", "event-replay", BASE_TIME, 0.21d, "model-v1")));
        writer.write(mapper.toDocument(event(
                "txn-replay",
                "event-replay",
                BASE_TIME.plusSeconds(30),
                0.99d,
                "model-conflict"
        )));

        ScoredTransactionDocument stored = stored("txn-replay");

        assertThat(stored.getSourceEventCreatedAt()).isEqualTo(BASE_TIME.toString());
        assertThat(stored.getFraudScore()).isEqualTo(0.21d);
        assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), ScoredTransactionDocument.class))
                .isEqualTo(1L);
    }

    @Test
    void laterLegitimateOccurrenceWinsAcrossModelVersions() {
        writer.write(mapper.toDocument(event("txn-model", "event-model-v1", BASE_TIME, 0.31d, "model-v1")));
        writer.write(mapper.toDocument(event(
                "txn-model",
                "event-model-v2",
                BASE_TIME.plusNanos(1),
                0.82d,
                "model-v2"
        )));

        ScoredTransactionDocument stored = stored("txn-model");

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
                "model-v2"
        )));
        writer.write(mapper.toDocument(event(
                "txn-out-of-order",
                "event-earlier",
                BASE_TIME,
                0.22d,
                "model-v1"
        )));

        assertThat(stored("txn-out-of-order").getSourceEventId()).isEqualTo("event-later");
        assertThat(stored("txn-out-of-order").getFraudScore()).isEqualTo(0.88d);
    }

    @Test
    void equalEventTimestampsUseSourceEventIdAsDeterministicTieBreaker() {
        writer.write(mapper.toDocument(event("txn-tie", "event-z", BASE_TIME, 0.91d, "model-z")));
        writer.write(mapper.toDocument(event("txn-tie", "event-a", BASE_TIME, 0.11d, "model-a")));

        assertThat(stored("txn-tie").getSourceEventId()).isEqualTo("event-z");
        assertThat(stored("txn-tie").getFraudScore()).isEqualTo(0.91d);
    }

    @Test
    void concurrentWritesConvergeOnTheSameAuthoritativeOccurrence() throws Exception {
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                int occurrence = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    writer.write(mapper.toDocument(event(
                            "txn-concurrent",
                            "event-" + occurrence,
                            BASE_TIME.plusNanos(occurrence),
                            occurrence / 10.0d,
                            "model-" + occurrence
                    )));
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }

            assertThat(stored("txn-concurrent").getSourceEventId()).isEqualTo("event-7");
            assertThat(stored("txn-concurrent").getFraudScore()).isEqualTo(0.7d);
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

        writer.write(mapper.toDocument(event(
                "txn-historical",
                "event-known",
                BASE_TIME,
                0.73d,
                "model-current"
        )));

        ScoredTransactionDocument stored = stored("txn-historical");
        assertThat(stored.getSourceEventId()).isEqualTo("event-known");
        assertThat(stored.getFraudScore()).isEqualTo(0.73d);
    }

    @Test
    void invalidOccurrenceIdentityIsRejectedBeforePersistence() {
        ScoredTransactionDocument candidate = mapper.toDocument(event(
                "txn-invalid-identity",
                "event-valid",
                BASE_TIME,
                0.42d,
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
            String modelVersion
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
                Map.of(),
                Map.of(),
                score >= 0.8d
        );
    }
}
