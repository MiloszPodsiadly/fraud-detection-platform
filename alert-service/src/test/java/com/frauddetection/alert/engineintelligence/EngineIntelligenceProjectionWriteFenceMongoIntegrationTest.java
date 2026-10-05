package com.frauddetection.alert.engineintelligence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class EngineIntelligenceProjectionWriteFenceMongoIntegrationTest {

    private static final Instant EARLIER = Instant.parse("2026-10-04T18:00:00Z");
    private static final Instant LATER = EARLIER.plusSeconds(1);

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private EngineIntelligenceProjectionWriteFence writeFence;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "engine_intelligence_occurrence_fence");
        mongoTemplate.dropCollection(EngineIntelligenceProjection.class);
        writeFence = new EngineIntelligenceProjectionWriteFence(mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void staleValidatedOccurrenceCannotReplaceNewerProjection() {
        EngineIntelligenceProjection earlier = projection("event-earlier", EARLIER, "a".repeat(64));
        EngineIntelligenceProjection later = projection("event-later", LATER, "b".repeat(64));

        assertThat(writeFence.write(later).status())
                .isEqualTo(EngineIntelligenceProjectionWriteResult.Status.ACCEPTED);
        assertThat(writeFence.write(earlier).status())
                .isEqualTo(EngineIntelligenceProjectionWriteResult.Status.STALE);

        assertThat(stored().getSourceEventId()).isEqualTo("event-later");
        assertThat(stored().getSourceEventFingerprint()).isEqualTo("b".repeat(64));
    }

    @Test
    void staleOccurrenceIsClassifiedWithoutAbortingActiveMongoTransaction() {
        EngineIntelligenceProjection earlier = projection("event-earlier", EARLIER, "a".repeat(64));
        EngineIntelligenceProjection later = projection("event-later", LATER, "b".repeat(64));
        TransactionTemplate transaction = new TransactionTemplate(
                new MongoTransactionManager(mongoTemplate.getMongoDatabaseFactory())
        );
        assertThat(writeFence.write(later).status())
                .isEqualTo(EngineIntelligenceProjectionWriteResult.Status.ACCEPTED);

        EngineIntelligenceProjectionWriteResult result = transaction.execute(status -> writeFence.write(earlier));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(EngineIntelligenceProjectionWriteResult.Status.STALE);
        assertThat(stored().getSourceEventId()).isEqualTo("event-later");
    }

    @Test
    void newerOccurrenceAndItsDuplicateRemainIdempotent() {
        EngineIntelligenceProjection earlier = projection("event-earlier", EARLIER, "a".repeat(64));
        EngineIntelligenceProjection later = projection("event-later", LATER, "b".repeat(64));

        assertThat(writeFence.write(earlier).status())
                .isEqualTo(EngineIntelligenceProjectionWriteResult.Status.ACCEPTED);
        assertThat(writeFence.write(later).status())
                .isEqualTo(EngineIntelligenceProjectionWriteResult.Status.ACCEPTED);
        assertThat(writeFence.write(later).status())
                .isEqualTo(EngineIntelligenceProjectionWriteResult.Status.ACCEPTED);

        assertThat(mongoTemplate.count(
                new org.springframework.data.mongodb.core.query.Query(),
                EngineIntelligenceProjection.class
        )).isEqualTo(1L);
        assertThat(stored().getSourceEventId()).isEqualTo("event-later");
    }

    @Test
    void sameSourceEventWithDifferentFingerprintFailsClosed() {
        EngineIntelligenceProjection accepted = projection("event-current", LATER, "a".repeat(64));
        EngineIntelligenceProjection conflicting = projection("event-current", LATER, "b".repeat(64));

        assertThat(writeFence.write(accepted).status())
                .isEqualTo(EngineIntelligenceProjectionWriteResult.Status.ACCEPTED);
        assertThat(writeFence.write(conflicting).status())
                .isEqualTo(EngineIntelligenceProjectionWriteResult.Status.SOURCE_PAYLOAD_CONFLICT);

        assertThat(stored().getSourceEventFingerprint()).isEqualTo("a".repeat(64));
    }

    @Test
    void historicalProjectionWithPartialFenceMetadataCanBeReplacedOnce() {
        mongoTemplate.getCollection("engine_intelligence_projections").insertOne(new Document()
                .append("_id", "transaction-current")
                .append("sourceEventCreatedAt", Date.from(EARLIER)));

        EngineIntelligenceProjection current = projection("event-current", LATER, "a".repeat(64));

        assertThat(writeFence.write(current).status())
                .isEqualTo(EngineIntelligenceProjectionWriteResult.Status.ACCEPTED);
        assertThat(stored().getSourceEventId()).isEqualTo("event-current");
        assertThat(stored().getSourceEventFingerprint()).isEqualTo("a".repeat(64));
    }

    private EngineIntelligenceProjection projection(String eventId, Instant sourceCreatedAt, String fingerprint) {
        return new EngineIntelligenceProjectionMapper(
                new EngineIntelligenceProjectionPolicy(),
                Clock.fixed(sourceCreatedAt, ZoneOffset.UTC)
        ).map(
                "transaction-current",
                eventId,
                sourceCreatedAt,
                fingerprint,
                EngineIntelligenceProjectionTestFixtures.minimalSummary(),
                null
        ).projection().orElseThrow();
    }

    private EngineIntelligenceProjection stored() {
        return mongoTemplate.findById("transaction-current", EngineIntelligenceProjection.class);
    }
}
