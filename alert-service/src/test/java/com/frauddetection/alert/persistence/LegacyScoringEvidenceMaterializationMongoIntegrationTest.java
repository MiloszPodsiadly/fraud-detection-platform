package com.frauddetection.alert.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LegacyScoringEvidenceMaterializationMongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "legacy_scoring_evidence_hard_cut");
        mongoTemplate.dropCollection(AlertDocument.class);
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void rawLegacyAlertEvidenceFailsClosedDuringCurrentMaterialization() {
        Document legacyEvidence = new Document("evidenceId", "legacy-evidence-1")
                .append("evidenceType", "DIAGNOSTIC")
                .append("source", "FRAUD_SCORING_SERVICE")
                .append("status", "LEGACY")
                .append("severity", "LOW")
                .append("title", "Historical fallback diagnostic")
                .append("description", "Historical fallback evidence requiring offline cutover")
                .append("attributes", new Document("diagnostic", true)
                        .append("fallbackUsed", true)
                        .append("supportedEvidenceCreated", false)
                        .append("reasonCodeApplicable", false)
                        .append("evidenceProjectionState", "LEGACY_PROJECTED"))
                .append("observedAt", Date.from(Instant.parse("2026-05-18T10:00:00Z")))
                .append("projectedAt", Date.from(Instant.parse("2026-05-18T10:01:00Z")));
        mongoTemplate.getCollection("alerts").insertOne(
                new Document("_id", "alert-with-legacy-evidence")
                        .append("evidenceSnapshot", List.of(legacyEvidence))
        );

        assertThatThrownBy(() -> mongoTemplate.findById("alert-with-legacy-evidence", AlertDocument.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No enum constant com.frauddetection.alert.evidence.EvidenceStatus.LEGACY");
    }
}
