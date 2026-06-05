package com.frauddetection.alert.engineintelligence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class EngineIntelligenceProjectionMongoIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-06-02T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-02T08:05:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private EngineIntelligenceProjectionRepository repository;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "fdp95_engine_intelligence_projection");
        mongoTemplate.dropCollection(EngineIntelligenceProjection.class);
        repository = new MongoRepositoryFactory(mongoTemplate)
                .getRepository(EngineIntelligenceProjectionRepository.class);
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void sameTransactionProjectionReplacesDocumentInsteadOfAppending() {
        var event = EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.fullSummary()
        );

        serviceAt(T1).project(event);
        serviceAt(T2).project(event);

        EngineIntelligenceProjection projection = repository.findById("txn-fdp95-001").orElseThrow();
        assertThat(repository.count()).isEqualTo(1L);
        assertThat(projection.getCreatedAt()).isEqualTo(T1);
        assertThat(projection.getUpdatedAt()).isEqualTo(T2);
        assertThat(projection.getEngineCount()).isEqualTo(2);
        assertThat(projection.getDiagnosticSignalCount()).isEqualTo(2);
        assertThat(projection.getWarningCount()).isEqualTo(2);
        assertThat(projection.getEngines()).hasSize(2);
        assertThat(projection.getDiagnosticSignals()).hasSize(2);
        assertThat(projection.getWarnings()).hasSize(2);
    }

    @Test
    void oldEventWithoutEngineIntelligenceCreatesNoMongoProjection() {
        serviceAt(T1).project(EngineIntelligenceProjectionTestFixtures.oldEvent());

        assertThat(repository.count()).isZero();
        assertThat(repository.findById("txn-fdp95-001")).isEmpty();
    }

    private EngineIntelligenceProjectionService serviceAt(Instant instant) {
        return new EngineIntelligenceProjectionService(
                repository,
                new EngineIntelligenceProjectionMapper(
                        new EngineIntelligenceProjectionPolicy(),
                        Clock.fixed(instant, ZoneOffset.UTC)
                ),
                new AlertServiceMetrics(new SimpleMeterRegistry())
        );
    }
}
