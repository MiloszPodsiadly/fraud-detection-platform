package com.frauddetection.alert.outbox;

import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Tag("invariant-proof")
class TransactionalOutboxPersistedContractPreflightIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private TransactionalOutboxPersistedContractPreflight preflight;

    @BeforeEach
    void setUp() {
        String databaseName = "outbox_preflight_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        preflight = new TransactionalOutboxPersistedContractPreflight(mongoTemplate);
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
    void aggregationBackedInspectRejectsEqualRevisionSemanticCorruption() {
        insert(publishedSource(), publishedAlert("FAILED_TERMINAL"));

        TransactionalOutboxPersistedContractPreflight.Report report = preflight.inspect(10);

        assertThat(report.blocksStartup()).isTrue();
        assertThat(report.samples().getFirst().violations()).contains("ALERT_STATUS_DOES_NOT_MATCH_SOURCE");
    }

    @Test
    void aggregationBackedInspectAcceptsScheduledProjectionDivergence() {
        Document source = publishedSource()
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Date.from(Instant.parse("2026-10-01T10:10:00Z")));
        insert(source, publishedAlert("FAILED_TERMINAL"));

        assertThat(preflight.inspect(10).blocksStartup()).isFalse();
    }

    @Test
    void aggregationBackedInspectAcceptsCanonicalPair() {
        insert(publishedSource(), publishedAlert("PUBLISHED"));

        assertThat(preflight.inspect(10).blocksStartup()).isFalse();
    }

    @Test
    void aggregationBackedInspectScansInvalidRecordsBeyondDiagnosticSampleLimit() {
        for (int index = 0; index < 3; index++) {
            String suffix = Integer.toString(index);
            Document source = publishedSource("event-" + suffix, "alert-" + suffix);
            source.remove("projection_revision");
            insert(source, publishedAlert("event-" + suffix, "alert-" + suffix, "PUBLISHED"));
        }

        TransactionalOutboxPersistedContractPreflight.Report report = preflight.inspect(1);

        assertThat(report.unsupportedTerminalCount()).isEqualTo(3);
        assertThat(report.samples()).hasSize(1);
    }

    @Test
    void aggregationBackedInspectFailsWhenSharedStartupBudgetExpires() {
        TransactionalOutboxPersistedContractPreflight timedPreflight =
                new TransactionalOutboxPersistedContractPreflight(mongoTemplate, Duration.ofNanos(1));

        assertThatThrownBy(() -> timedPreflight.inspect(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup validation budget exceeded");
    }

    private void insert(Document source, Document alert) {
        mongoTemplate.getCollection(TransactionalOutboxPersistedContractPreflight.COLLECTION).insertOne(source);
        mongoTemplate.getCollection(TransactionalOutboxPersistedContractPreflight.ALERT_COLLECTION).insertOne(alert);
    }

    private Document publishedSource() {
        return publishedSource("event-1", "alert-1");
    }

    private Document publishedSource(String eventId, String alertId) {
        return new Document("_id", eventId)
                .append("resource_type", "ALERT")
                .append("resource_id", alertId)
                .append("status", "PUBLISHED")
                .append("attempts", 0)
                .append("projection_revision", 5L)
                .append("resolution_pending", false)
                .append("published_at", Date.from(Instant.parse("2026-10-01T10:00:00Z")))
                .append("publication_confirmation_provenance", "BROKER_ACKNOWLEDGED");
    }

    private Document publishedAlert(String status) {
        return publishedAlert("event-1", "alert-1", status);
    }

    private Document publishedAlert(String eventId, String alertId, String status) {
        Document alert = new Document("_id", alertId)
                .append("decisionOutboxEventId", eventId)
                .append("decisionOutboxProjectionRevision", 5L)
                .append("decisionOutboxStatus", status)
                .append("decisionOutboxAttempts", 0);
        if ("PUBLISHED".equals(status)) {
            alert.append("decisionOutboxPublishedAt", Date.from(Instant.parse("2026-10-01T10:00:00Z")))
                    .append("decisionOutboxPublicationConfirmationProvenance", "BROKER_ACKNOWLEDGED");
        }
        return alert;
    }
}
