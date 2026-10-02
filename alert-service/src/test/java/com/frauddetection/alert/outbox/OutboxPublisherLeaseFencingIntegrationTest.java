package com.frauddetection.alert.outbox;

import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@Tag("integration")
@Tag("invariant-proof")
class OutboxPublisherLeaseFencingIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private TransactionalOutboxRecordRepository repository;

    @BeforeEach
    void setUp() {
        String databaseName = "outbox_fencing_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        repository = new MongoRepositoryFactory(mongoTemplate)
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
    void sameCoordinatorRejectsDelayedTransitionFromReplacedClaimGeneration() {
        repository.save(pending("event-same-coordinator"));
        OutboxPublisherCoordinator coordinator = coordinator();

        TransactionalOutboxRecordDocument claimA = coordinator.claimNext();
        expire(claimA);
        TransactionalOutboxRecordDocument claimB = coordinator.claimNext();

        assertThat(claimA.getLeaseOwner()).isEqualTo(claimB.getLeaseOwner());
        assertThat(claimA.getLeaseClaimToken()).isNotEqualTo(claimB.getLeaseClaimToken());
        assertThat(coordinator.markPublishAttempted(claimA)).isFalse();
        assertThat(coordinator.markPublishAttempted(claimB)).isTrue();
        assertCurrentClaim("event-same-coordinator", claimB, TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
    }

    @Test
    void differentCoordinatorRejectsDelayedTransitionFromReplacedClaimGeneration() {
        repository.save(pending("event-different-coordinator"));
        OutboxPublisherCoordinator coordinatorA = coordinator();
        OutboxPublisherCoordinator coordinatorB = coordinator();

        TransactionalOutboxRecordDocument claimA = coordinatorA.claimNext();
        expire(claimA);
        TransactionalOutboxRecordDocument claimB = coordinatorB.claimNext();

        assertThat(claimA.getLeaseOwner()).isNotEqualTo(claimB.getLeaseOwner());
        assertThat(coordinatorA.markPublishAttempted(claimA)).isFalse();
        assertThat(coordinatorB.markPublishAttempted(claimB)).isTrue();
        assertCurrentClaim("event-different-coordinator", claimB, TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
    }

    @Test
    void staleFailureCannotClearCurrentClaim() {
        repository.save(pending("event-stale-failure"));
        OutboxPublisherCoordinator coordinator = coordinator();

        TransactionalOutboxRecordDocument claimA = coordinator.claimNext();
        expire(claimA);
        TransactionalOutboxRecordDocument claimB = coordinator.claimNext();

        assertThat(coordinator.markFailed(
                claimA,
                TransactionalOutboxStatus.FAILED_RETRYABLE,
                "STALE_WORKER_FAILURE"
        )).isFalse();
        assertCurrentClaim("event-stale-failure", claimB, TransactionalOutboxStatus.PROCESSING);
    }

    @Test
    void staleConfirmationCannotReplaceCurrentPublishAttempt() {
        repository.save(pending("event-stale-confirmation"));
        OutboxPublisherCoordinator coordinator = coordinator();

        TransactionalOutboxRecordDocument claimA = coordinator.claimNext();
        expire(claimA);
        TransactionalOutboxRecordDocument claimB = coordinator.claimNext();
        assertThat(coordinator.markPublishAttempted(claimB)).isTrue();

        assertThat(coordinator.markPublishConfirmationUnknown(claimA)).isFalse();
        assertCurrentClaim("event-stale-confirmation", claimB, TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
        assertThat(coordinator.markOutboxRecordPublished(claimB)).isTrue();

        TransactionalOutboxRecordDocument published = repository.findById(claimB.getEventId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertThat(published.getLeaseOwner()).isNull();
        assertThat(published.getLeaseClaimToken()).isNull();
        assertThat(published.getLeaseExpiresAt()).isNull();
    }

    @Test
    void restartRecoveryClearsExpiredClaimGeneration() {
        TransactionalOutboxRecordDocument record = pending("event-restart-recovery");
        record.setStatus(TransactionalOutboxStatus.PROCESSING);
        record.setAttempts(1);
        record.setLeaseOwner("stopped-instance");
        record.setLeaseClaimToken("stopped-claim-generation");
        record.setLeaseExpiresAt(Instant.now().minusSeconds(60));
        repository.save(record);

        OutboxRecoveryService recoveryService = new OutboxRecoveryService(
                repository,
                mongoTemplate,
                mock(OutboxPublisherCoordinator.class),
                mock(RegulatedMutationCoordinator.class),
                mock(OutboxConfirmationResolutionMutationHandler.class),
                mock(AlertServiceMetrics.class),
                Duration.ZERO
        );

        OutboxRecoveryRunResponse response = recoveryService.recoverNow();
        TransactionalOutboxRecordDocument recovered = repository.findById(record.getEventId()).orElseThrow();

        assertThat(response.releasedStaleProcessing()).isOne();
        assertThat(recovered.getStatus()).isEqualTo(TransactionalOutboxStatus.FAILED_RETRYABLE);
        assertThat(recovered.getLeaseOwner()).isNull();
        assertThat(recovered.getLeaseClaimToken()).isNull();
        assertThat(recovered.getLeaseExpiresAt()).isNull();
    }

    @Test
    void exhaustedStaleProcessingBecomesTerminalAndItsFailedProjectionRemainsRecoverable() {
        TransactionalOutboxRecordDocument record = pending("event-exhausted-processing");
        record.setResourceId("alert-exhausted-processing");
        record.setStatus(TransactionalOutboxStatus.PROCESSING);
        record.setAttempts(1);
        record.setLeaseOwner("stopped-instance");
        record.setLeaseClaimToken("stopped-generation");
        record.setLeaseExpiresAt(Instant.now().minusSeconds(60));
        repository.save(record);
        FraudDecisionEventPublisher broker = mock(FraudDecisionEventPublisher.class);
        OutboxPublisherCoordinator coordinator = new OutboxPublisherCoordinator(
                broker,
                mongoTemplate,
                mock(AlertServiceMetrics.class),
                Duration.ofMinutes(1),
                1
        );
        OutboxRecoveryService recoveryService = recoveryService(repository, coordinator);

        recoveryService.recoverNow();

        TransactionalOutboxRecordDocument exhausted = repository.findById(record.getEventId()).orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(TransactionalOutboxStatus.FAILED_TERMINAL);
        assertThat(exhausted.getLastError()).isEqualTo("RETRY_BUDGET_EXHAUSTED_BEFORE_PUBLISH_ATTEMPT");
        assertThat(exhausted.getProjectionRevision()).isEqualTo(1L);
        assertThat(exhausted.getProjectionReconcileAfter()).isNotNull();
        assertThat(exhausted.isProjectionMismatch()).isTrue();
        assertThat(recoveryService.backlog().failedTerminalCount()).isOne();
        assertThat(recoveryService.backlog().failedRetryableCount()).isZero();
        verify(broker, never()).publish(org.mockito.ArgumentMatchers.any());

        AlertDocument alert = new AlertDocument();
        alert.setAlertId(record.getResourceId());
        alert.setDecisionOutboxEventId(record.getEventId());
        alert.setDecisionOutboxProjectionRevision(0L);
        alert.setDecisionOutboxStatus("PROCESSING");
        mongoTemplate.save(alert);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(record.getEventId())),
                new Update().set("projection_reconcile_after", Instant.now().minusSeconds(1)),
                TransactionalOutboxRecordDocument.class
        );

        recoveryService.recoverNow();

        AlertDocument repaired = mongoTemplate.findById(record.getResourceId(), AlertDocument.class);
        TransactionalOutboxRecordDocument reconciled = repository.findById(record.getEventId()).orElseThrow();
        assertThat(repaired.getDecisionOutboxStatus()).isEqualTo("FAILED_TERMINAL");
        assertThat(repaired.getDecisionOutboxProjectionRevision()).isEqualTo(1L);
        assertThat(reconciled.isProjectionMismatch()).isFalse();
        assertThat(reconciled.getProjectionReconcileAfter()).isNull();
    }

    @Test
    void expiredPublishAttemptRemainsConfirmationUnknownAfterRetryBudgetIsConsumed() {
        TransactionalOutboxRecordDocument record = pending("event-ambiguous-attempt");
        record.setResourceId("alert-ambiguous-attempt");
        record.setStatus(TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
        record.setAttempts(1);
        record.setLeaseOwner("stopped-instance");
        record.setLeaseClaimToken("stopped-generation");
        record.setLeaseExpiresAt(Instant.now().minusSeconds(60));
        repository.save(record);
        AlertDocument alert = alertProjection(record, "PROCESSING");
        mongoTemplate.save(alert);
        OutboxRecoveryService recoveryService = recoveryService(repository, new OutboxPublisherCoordinator(
                mock(FraudDecisionEventPublisher.class),
                mongoTemplate,
                mock(AlertServiceMetrics.class),
                Duration.ofMinutes(1),
                1
        ));

        recoveryService.recoverNow();

        TransactionalOutboxRecordDocument recovered = repository.findById(record.getEventId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        assertThat(recovered.getStatus()).isNotEqualTo(TransactionalOutboxStatus.FAILED_TERMINAL);
        assertThat(recovered.getLastError()).isEqualTo("STALE_PUBLISH_ATTEMPT_CONFIRMATION_UNKNOWN");
        assertThat(recovered.getProjectionRevision()).isEqualTo(1L);
    }

    @Test
    void staleProcessingBelowRetryLimitRemainsClaimable() {
        TransactionalOutboxRecordDocument record = pending("event-retryable-processing");
        record.setResourceId("alert-retryable-processing");
        record.setStatus(TransactionalOutboxStatus.PROCESSING);
        record.setAttempts(1);
        record.setLeaseOwner("stopped-instance");
        record.setLeaseClaimToken("stopped-generation");
        record.setLeaseExpiresAt(Instant.now().minusSeconds(60));
        repository.save(record);
        mongoTemplate.save(alertProjection(record, "PROCESSING"));
        OutboxPublisherCoordinator recoveryCoordinator = spy(new OutboxPublisherCoordinator(
                mock(FraudDecisionEventPublisher.class),
                mongoTemplate,
                mock(AlertServiceMetrics.class),
                Duration.ofMinutes(1),
                2
        ));
        doReturn(0).when(recoveryCoordinator).publishPending(100);

        recoveryService(repository, recoveryCoordinator).recoverNow();

        TransactionalOutboxRecordDocument retryable = repository.findById(record.getEventId()).orElseThrow();
        assertThat(retryable.getStatus()).isEqualTo(TransactionalOutboxStatus.FAILED_RETRYABLE);
        OutboxPublisherCoordinator nextPublisher = new OutboxPublisherCoordinator(
                mock(FraudDecisionEventPublisher.class),
                mongoTemplate,
                mock(AlertServiceMetrics.class),
                Duration.ofMinutes(1),
                2
        );
        TransactionalOutboxRecordDocument claimed = nextPublisher.claimNext();
        assertThat(claimed).isNotNull();
        assertThat(claimed.getStatus()).isEqualTo(TransactionalOutboxStatus.PROCESSING);
        assertThat(claimed.getAttempts()).isEqualTo(2);
    }

    @Test
    void alreadyExhaustedRetryableIsFinalizedInsteadOfBeingIgnored() {
        TransactionalOutboxRecordDocument record = pending("event-exhausted-retryable");
        record.setResourceId("alert-exhausted-retryable");
        record.setStatus(TransactionalOutboxStatus.FAILED_RETRYABLE);
        record.setAttempts(1);
        repository.save(record);
        OutboxRecoveryService recoveryService = recoveryService(repository, new OutboxPublisherCoordinator(
                mock(FraudDecisionEventPublisher.class),
                mongoTemplate,
                mock(AlertServiceMetrics.class),
                Duration.ofMinutes(1),
                1
        ));

        recoveryService.recoverNow();

        TransactionalOutboxRecordDocument recovered = repository.findById(record.getEventId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(TransactionalOutboxStatus.FAILED_TERMINAL);
        assertThat(recovered.getLastError()).isEqualTo("RETRY_BUDGET_EXHAUSTED_BEFORE_PUBLISH_ATTEMPT");
        assertThat(recovered.getProjectionRevision()).isEqualTo(1L);
        assertThat(recoveryService.backlog().failedTerminalCount()).isOne();
    }

    private OutboxPublisherCoordinator coordinator() {
        return new OutboxPublisherCoordinator(
                mock(FraudDecisionEventPublisher.class),
                mongoTemplate,
                mock(AlertServiceMetrics.class),
                Duration.ofMinutes(5),
                5
        );
    }

    private OutboxRecoveryService recoveryService(
            TransactionalOutboxRecordRepository outboxRepository,
            OutboxPublisherCoordinator coordinator
    ) {
        return new OutboxRecoveryService(
                outboxRepository,
                mongoTemplate,
                coordinator,
                mock(RegulatedMutationCoordinator.class),
                mock(OutboxConfirmationResolutionMutationHandler.class),
                mock(AlertServiceMetrics.class),
                Duration.ZERO
        );
    }

    private AlertDocument alertProjection(TransactionalOutboxRecordDocument record, String status) {
        AlertDocument alert = new AlertDocument();
        alert.setAlertId(record.getResourceId());
        alert.setDecisionOutboxEventId(record.getEventId());
        alert.setDecisionOutboxProjectionRevision(record.getProjectionRevision());
        alert.setDecisionOutboxStatus(status);
        alert.setDecisionOutboxAttempts(record.getAttempts());
        return alert;
    }

    private void expire(TransactionalOutboxRecordDocument claim) {
        mongoTemplate.updateFirst(
                Query.query(new Criteria().andOperator(
                        Criteria.where("_id").is(claim.getEventId()),
                        Criteria.where("lease_claim_token").is(claim.getLeaseClaimToken())
                )),
                new Update().set("lease_expires_at", Instant.now().minusSeconds(1)),
                TransactionalOutboxRecordDocument.class
        );
    }

    private void assertCurrentClaim(
            String eventId,
            TransactionalOutboxRecordDocument expectedClaim,
            TransactionalOutboxStatus expectedStatus
    ) {
        TransactionalOutboxRecordDocument persisted = repository.findById(eventId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(expectedStatus);
        assertThat(persisted.getLeaseOwner()).isEqualTo(expectedClaim.getLeaseOwner());
        assertThat(persisted.getLeaseClaimToken()).isEqualTo(expectedClaim.getLeaseClaimToken());
        assertThat(persisted.getLeaseExpiresAt()).isEqualTo(expectedClaim.getLeaseExpiresAt());
    }

    private TransactionalOutboxRecordDocument pending(String eventId) {
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId(eventId);
        record.setDedupeKey("dedupe-" + eventId);
        record.setMutationCommandId("command-" + eventId);
        record.setResourceType("ALERT");
        record.setStatus(TransactionalOutboxStatus.PENDING);
        record.setCreatedAt(Instant.now());
        record.setUpdatedAt(Instant.now());
        return record;
    }
}
