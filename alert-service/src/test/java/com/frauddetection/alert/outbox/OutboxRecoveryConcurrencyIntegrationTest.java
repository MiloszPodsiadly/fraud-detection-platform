package com.frauddetection.alert.outbox;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.mutation.outbox.OutboxConfirmationResolutionMutationHandler;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@Tag("integration")
@Tag("invariant-proof")
class OutboxRecoveryConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private TransactionalOutboxRecordRepository actualRepository;

    @BeforeEach
    void setUp() {
        String databaseName = "outbox_recovery_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        actualRepository = new MongoRepositoryFactory(mongoTemplate)
                .getRepository(TransactionalOutboxRecordRepository.class);
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
    void staleRecoveryCannotOverwriteRenewedProcessingLease() {
        TransactionalOutboxRecordDocument original = record("event-processing", TransactionalOutboxStatus.PROCESSING);
        actualRepository.save(original);
        TransactionalOutboxRecordRepository intercepted = interceptedRepository();

        doAnswer(invocation -> {
            List<TransactionalOutboxRecordDocument> stale = actualRepository
                    .findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                            TransactionalOutboxStatus.PROCESSING,
                            invocation.getArgument(1)
                    );
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(original.getEventId())),
                    new Update()
                            .set("lease_owner", "new-owner")
                            .set("lease_expires_at", Instant.now().plusSeconds(300))
                            .set("updated_at", Instant.now())
                            .inc("attempts", 1),
                    TransactionalOutboxRecordDocument.class
            );
            return stale;
        }).when(intercepted).findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                eq(TransactionalOutboxStatus.PROCESSING),
                any()
        );

        OutboxRecoveryRunResponse response = service(intercepted).recoverNow();
        TransactionalOutboxRecordDocument persisted = actualRepository.findById(original.getEventId()).orElseThrow();

        assertThat(response.releasedStaleProcessing()).isZero();
        assertThat(persisted.getStatus()).isEqualTo(TransactionalOutboxStatus.PROCESSING);
        assertThat(persisted.getLeaseOwner()).isEqualTo("new-owner");
        assertThat(persisted.getAttempts()).isEqualTo(2);
    }

    @Test
    void staleRecoveryCannotOverwritePublishedTerminalState() {
        TransactionalOutboxRecordDocument original = record(
                "event-publish-attempted",
                TransactionalOutboxStatus.PUBLISH_ATTEMPTED
        );
        actualRepository.save(original);
        TransactionalOutboxRecordRepository intercepted = interceptedRepository();
        Instant publishedAt = Instant.parse("2026-09-30T10:05:00Z");

        doAnswer(invocation -> {
            List<TransactionalOutboxRecordDocument> stale = actualRepository
                    .findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                            TransactionalOutboxStatus.PUBLISH_ATTEMPTED,
                            invocation.getArgument(1)
                    );
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(original.getEventId())),
                    new Update()
                            .set("status", TransactionalOutboxStatus.PUBLISHED)
                            .set("published_at", publishedAt)
                            .set("updated_at", Instant.now())
                            .unset("lease_owner")
                            .unset("lease_expires_at"),
                    TransactionalOutboxRecordDocument.class
            );
            return stale;
        }).when(intercepted).findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                eq(TransactionalOutboxStatus.PUBLISH_ATTEMPTED),
                any()
        );

        OutboxRecoveryRunResponse response = service(intercepted).recoverNow();
        TransactionalOutboxRecordDocument persisted = actualRepository.findById(original.getEventId()).orElseThrow();

        assertThat(response.publishAttemptedMarkedUnknown()).isZero();
        assertThat(persisted.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertThat(persisted.getPublishedAt()).isEqualTo(publishedAt);
    }

    @ParameterizedTest
    @EnumSource(value = TransactionalOutboxStatus.class, names = {
            "PUBLISHED",
            "PUBLISH_CONFIRMATION_UNKNOWN",
            "FAILED_RETRYABLE",
            "FAILED_TERMINAL",
            "RECOVERY_REQUIRED"
    })
    void staleProjectionRepairCannotOverwriteNewerProjection(TransactionalOutboxStatus status) {
        TransactionalOutboxRecordDocument original = record("event-projection-" + status.name(), status);
        original.setProjectionMismatch(true);
        original.setProjectionMismatchReason("ALERT_PROJECTION_UPDATE_FAILED");
        original.setPublishedAt(Instant.parse("2026-09-30T10:01:00Z"));
        original.setLastError(status == TransactionalOutboxStatus.PUBLISHED ? null : "source-error");
        actualRepository.save(original);

        AlertDocument alert = new AlertDocument();
        alert.setAlertId("alert-1");
        alert.setDecisionOutboxStatus("PENDING");
        Instant newerPublishedAt = Instant.parse("2026-09-30T10:10:00Z");
        mongoTemplate.save(alert);

        TransactionalOutboxRecordRepository intercepted = interceptedRepository();
        doAnswer(invocation -> {
            List<TransactionalOutboxRecordDocument> stale = actualRepository
                    .findTop100ByProjectionMismatchTrueOrderByCreatedAtAsc();
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is("alert-1")),
                    new Update()
                            .set("decisionOutboxStatus", "PUBLISHED")
                            .set("decisionOutboxPublishedAt", newerPublishedAt)
                            .set("decisionOutboxAttempts", original.getAttempts() + 1),
                    AlertDocument.class
            );
            return stale;
        }).when(intercepted).findTop100ByProjectionMismatchTrueOrderByCreatedAtAsc();

        OutboxRecoveryRunResponse response = service(intercepted).recoverNow();
        AlertDocument persisted = mongoTemplate.findById("alert-1", AlertDocument.class);
        TransactionalOutboxRecordDocument persistedOutbox = actualRepository.findById(original.getEventId()).orElseThrow();

        assertThat(response.projectionRepaired()).isZero();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getDecisionOutboxStatus()).isEqualTo("PUBLISHED");
        assertThat(persisted.getDecisionOutboxPublishedAt()).isEqualTo(newerPublishedAt);
        assertThat(persistedOutbox.isProjectionMismatch()).isTrue();
        assertThat(persistedOutbox.getProjectionMismatchReason()).isEqualTo("ALERT_PROJECTION_REPAIR_FAILED");
    }

    @SuppressWarnings("unchecked")
    private TransactionalOutboxRecordRepository interceptedRepository() {
        return mock(TransactionalOutboxRecordRepository.class, delegatesTo(actualRepository));
    }

    private OutboxRecoveryService service(TransactionalOutboxRecordRepository repository) {
        OutboxPublisherCoordinator publisherCoordinator = mock(OutboxPublisherCoordinator.class);
        return new OutboxRecoveryService(
                repository,
                mongoTemplate,
                publisherCoordinator,
                mock(RegulatedMutationCoordinator.class),
                mock(OutboxConfirmationResolutionMutationHandler.class),
                mock(AlertServiceMetrics.class),
                Duration.ofMinutes(2)
        );
    }

    private TransactionalOutboxRecordDocument record(String eventId, TransactionalOutboxStatus status) {
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId(eventId);
        record.setDedupeKey("dedupe-" + eventId);
        record.setMutationCommandId("command-" + eventId);
        record.setResourceType("ALERT");
        record.setResourceId("alert-1");
        record.setEventType("FRAUD_DECISION");
        record.setPayloadHash("payload-hash");
        record.setStatus(status);
        record.setAttempts(1);
        record.setLeaseOwner("old-owner");
        record.setLeaseExpiresAt(Instant.now().minusSeconds(600));
        record.setCreatedAt(Instant.parse("2026-09-30T10:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-09-30T10:00:00Z"));
        return record;
    }
}
