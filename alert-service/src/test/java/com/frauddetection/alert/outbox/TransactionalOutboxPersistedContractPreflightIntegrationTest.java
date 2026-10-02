package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.regulated.mutation.outbox.OutboxConfirmationResolutionMutationHandler;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void aggregationBackedInspectRejectsPendingSourceWithPublishedProjection() {
        Document source = new Document("_id", "event-false-published")
                .append("resource_type", "ALERT")
                .append("resource_id", "alert-false-published")
                .append("status", "PENDING")
                .append("attempts", 0)
                .append("projection_revision", 0L)
                .append("resolution_pending", false);
        Document alert = new Document("_id", "alert-false-published")
                .append("decisionOutboxEventId", "event-false-published")
                .append("decisionOutboxProjectionRevision", 0L)
                .append("decisionOutboxStatus", "PUBLISHED")
                .append("decisionOutboxAttempts", 0)
                .append("decisionOutboxPublishedAt", Date.from(Instant.parse("2026-10-01T10:00:00Z")))
                .append("decisionOutboxPublicationConfirmationProvenance", "BROKER_ACKNOWLEDGED");
        insert(source, alert);

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
    void aggregationBackedInspectRejectsMarkerMaskedFalsePublication() {
        Document source = new Document("_id", "event-marker-masked")
                .append("resource_type", "ALERT")
                .append("resource_id", "alert-marker-masked")
                .append("status", "FAILED_TERMINAL")
                .append("attempts", 3)
                .append("projection_revision", 5L)
                .append("last_error", "RETRY_EXHAUSTED")
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Date.from(Instant.parse("2026-10-01T10:10:00Z")))
                .append("resolution_pending", false);
        Document alert = new Document("_id", "alert-marker-masked")
                .append("decisionOutboxEventId", "event-marker-masked")
                .append("decisionOutboxProjectionRevision", 4L)
                .append("decisionOutboxStatus", "PUBLISHED")
                .append("decisionOutboxAttempts", 2)
                .append("decisionOutboxPublishedAt", Date.from(Instant.parse("2026-10-01T10:00:00Z")))
                .append("decisionOutboxPublicationConfirmationProvenance", "BROKER_ACKNOWLEDGED");
        insert(source, alert);

        TransactionalOutboxPersistedContractPreflight.Report report = preflight.inspect(10);

        assertThat(report.blocksStartup()).isTrue();
        assertThat(report.samples().getFirst().violations()).contains(
                "ALERT_STATUS_DOES_NOT_MATCH_SOURCE",
                "ALERT_PUBLISHED_AT_DOES_NOT_MATCH_SOURCE",
                "ALERT_PUBLICATION_PROVENANCE_DOES_NOT_MATCH_SOURCE"
        );
    }

    @Test
    void aggregationBackedInspectRejectsMarkerMaskedFalsePublishedProvenance() {
        Document source = publishedSource()
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Date.from(Instant.parse("2026-10-01T10:10:00Z")));
        Document alert = publishedAlert("PUBLISHED")
                .append("decisionOutboxPublicationConfirmationProvenance", "MANUAL_DUAL_CONTROL_ATTESTED")
                .append("decisionOutboxPublishedAt", Date.from(Instant.parse("2026-10-01T10:00:01Z")));
        insert(source, alert);

        TransactionalOutboxPersistedContractPreflight.Report report = preflight.inspect(10);

        assertThat(report.blocksStartup()).isTrue();
        assertThat(report.samples().getFirst().violations()).contains(
                "ALERT_PUBLICATION_PROVENANCE_DOES_NOT_MATCH_SOURCE",
                "ALERT_PUBLISHED_AT_DOES_NOT_MATCH_SOURCE"
        );
    }

    @Test
    void aggregationBackedInspectAcceptsCanonicalPair() {
        insert(publishedSource(), publishedAlert("PUBLISHED"));

        assertThat(preflight.inspect(10).blocksStartup()).isFalse();
    }

    @Test
    void aggregationBackedInspectIgnoresDefaultOutboxFieldsOnUndecidedAlert() {
        mongoTemplate.getCollection(TransactionalOutboxPersistedContractPreflight.ALERT_COLLECTION).insertOne(
                new Document("_id", "alert-without-decision")
                        .append("decisionOutboxProjectionRevision", 0L)
                        .append("decisionOutboxAttempts", 0)
                        .append("decisionOutboxResolutionPending", false)
        );

        TransactionalOutboxPersistedContractPreflight.Report report = preflight.inspect(10);

        assertThat(report.blocksStartup()).isFalse();
        assertThat(report.unsupportedAlertProjectionCount()).isZero();
    }

    @Test
    void recoveryRequiredProducedByMutationHandlerPassesAggregationBackedPreflight() {
        Document source = new Document("_id", "event-recovery")
                .append("resource_type", "ALERT")
                .append("resource_id", "alert-recovery")
                .append("status", "PUBLISH_CONFIRMATION_UNKNOWN")
                .append("attempts", 1)
                .append("projection_revision", 0L)
                .append("resolution_pending", false)
                .append("updated_at", Date.from(Instant.parse("2026-10-01T10:00:00Z")));
        Document alert = new Document("_id", "alert-recovery")
                .append("decisionOutboxEventId", "event-recovery")
                .append("decisionOutboxProjectionRevision", 0L)
                .append("decisionOutboxStatus", "PUBLISH_CONFIRMATION_UNKNOWN")
                .append("decisionOutboxAttempts", 1)
                .append("decisionOutboxLastError", "PUBLISH_CONFIRMATION_UNKNOWN")
                .append("decisionOutboxFailureReason", "PUBLISH_CONFIRMATION_UNKNOWN");
        insert(source, alert);
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        when(repository.findById("event-recovery")).thenAnswer(invocation -> mongoTemplate.findById(
                "event-recovery",
                TransactionalOutboxRecordDocument.class
        ) == null ? java.util.Optional.empty() : java.util.Optional.of(mongoTemplate.findById(
                "event-recovery",
                TransactionalOutboxRecordDocument.class
        )));
        OutboxConfirmationResolutionMutationHandler handler = new OutboxConfirmationResolutionMutationHandler(
                repository,
                mongoTemplate,
                false,
                false,
                java.time.Clock.fixed(Instant.parse("2026-10-01T10:10:00Z"), java.time.ZoneOffset.UTC)
        );

        TransactionalOutboxRecordDocument resolved = handler.resolve(
                "event-recovery",
                new OutboxConfirmationResolutionRequest(
                        OutboxConfirmationResolution.RECOVERY_REQUIRED,
                        null,
                        "runbook escalation",
                        new ResolutionEvidenceReference(
                                ResolutionEvidenceType.RUNBOOK_STEP,
                                "runbook=outbox,step=7",
                                Instant.parse("2026-10-01T10:09:00Z"),
                                "ops-verifier"
                        )
                ),
                "ops-operator"
        );

        assertThat(resolved.getStatus()).isEqualTo(TransactionalOutboxStatus.RECOVERY_REQUIRED);
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
