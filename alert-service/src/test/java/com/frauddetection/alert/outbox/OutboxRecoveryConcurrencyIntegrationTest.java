package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.mutation.outbox.OutboxConfirmationResolutionMutationHandler;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.bson.Document;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
    void competingSingleControlResolutionsAllowExactlyOneAuthoritativeTransition() throws Exception {
        TransactionalOutboxRecordDocument original = confirmationUnknownRecord("event-single-race");
        actualRepository.save(original);
        saveAlertProjection(original);
        TransactionalOutboxRecordRepository synchronizedReads = synchronizeFirstTwoReads(original.getEventId());
        OutboxConfirmationResolutionMutationHandler handler = new OutboxConfirmationResolutionMutationHandler(
                synchronizedReads,
                mongoTemplate,
                false,
                false
        );

        List<Attempt> attempts = runConcurrently(
                () -> attempt(() -> handler.resolve(
                        original.getEventId(),
                        resolution(OutboxConfirmationResolution.PUBLISHED, "publish confirmed"),
                        "ops-1"
                )),
                () -> attempt(() -> handler.resolve(
                        original.getEventId(),
                        resolution(OutboxConfirmationResolution.RECOVERY_REQUIRED, "recovery required"),
                        "ops-2"
                ))
        );

        TransactionalOutboxRecordDocument persisted = actualRepository.findById(original.getEventId()).orElseThrow();
        assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> !attempt.success()).hasSize(1);
        assertThat(persisted.getStatus()).isIn(
                TransactionalOutboxStatus.PUBLISHED,
                TransactionalOutboxStatus.RECOVERY_REQUIRED
        );
    }

    @Test
    void competingDualControlRequestsAllowExactlyOneRequester() throws Exception {
        TransactionalOutboxRecordDocument original = confirmationUnknownRecord("event-request-race");
        actualRepository.save(original);
        saveAlertProjection(original);
        TransactionalOutboxRecordRepository synchronizedReads = synchronizeFirstTwoReads(original.getEventId());
        OutboxConfirmationResolutionMutationHandler handler = new OutboxConfirmationResolutionMutationHandler(
                synchronizedReads,
                mongoTemplate,
                true,
                true
        );

        List<Attempt> attempts = runConcurrently(
                () -> attempt(() -> handler.resolve(
                        original.getEventId(),
                        resolution(OutboxConfirmationResolution.PUBLISHED, "request one"),
                        "ops-1"
                )),
                () -> attempt(() -> handler.resolve(
                        original.getEventId(),
                        resolution(OutboxConfirmationResolution.PUBLISHED, "request two"),
                        "ops-2"
                ))
        );

        TransactionalOutboxRecordDocument persisted = actualRepository.findById(original.getEventId()).orElseThrow();
        assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
        assertThat(persisted.isResolutionPending()).isTrue();
        assertThat(persisted.getResolutionRequestedBy()).isIn("ops-1", "ops-2");
    }

    @Test
    void competingDualControlApprovalsAllowExactlyOneApproval() throws Exception {
        Instant requesterTime = Instant.parse("2026-10-02T10:10:00Z");
        TransactionalOutboxRecordDocument original = confirmationUnknownRecord("event-approval-race");
        actualRepository.save(original);
        saveAlertProjection(original);
        OutboxConfirmationResolutionMutationHandler requester = new OutboxConfirmationResolutionMutationHandler(
                actualRepository,
                mongoTemplate,
                true,
                true,
                Clock.fixed(requesterTime, ZoneOffset.UTC)
        );
        TransactionalOutboxRecordDocument requested = requester.resolve(
                original.getEventId(),
                resolution(OutboxConfirmationResolution.PUBLISHED, "request reason"),
                "requester"
        );
        TransactionalOutboxRecordRepository synchronizedReads = synchronizeFirstTwoReads(original.getEventId());
        OutboxConfirmationResolutionMutationHandler firstApprover = new OutboxConfirmationResolutionMutationHandler(
                synchronizedReads,
                mongoTemplate,
                true,
                true,
                Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC)
        );
        OutboxConfirmationResolutionMutationHandler secondApprover = new OutboxConfirmationResolutionMutationHandler(
                synchronizedReads,
                mongoTemplate,
                true,
                true,
                Clock.fixed(Instant.parse("2026-10-02T09:55:00Z"), ZoneOffset.UTC)
        );

        List<Attempt> attempts = runConcurrently(
                () -> attempt(() -> firstApprover.resolve(
                        original.getEventId(),
                        resolution(
                                OutboxConfirmationResolution.PUBLISHED,
                                requested.getResolutionRequestId(),
                                "approval one"
                        ),
                        "approver-1"
                )),
                () -> attempt(() -> secondApprover.resolve(
                        original.getEventId(),
                        resolution(
                                OutboxConfirmationResolution.PUBLISHED,
                                requested.getResolutionRequestId(),
                                "approval two"
                        ),
                        "approver-2"
                ))
        );

        TransactionalOutboxRecordDocument persisted = actualRepository.findById(original.getEventId()).orElseThrow();
        AlertDocument projection = mongoTemplate.findById("alert-1", AlertDocument.class);
        assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
        assertThat(persisted.isResolutionPending()).isFalse();
        assertThat(persisted.getResolutionApprovedBy()).isIn("approver-1", "approver-2");
        assertThat(persisted.getResolutionRequestReason()).isEqualTo("request reason");
        assertThat(persisted.getResolutionApprovalReason()).isIn("approval one", "approval two");
        assertThat(projection).isNotNull();
        assertThat(projection.getDecisionOutboxResolutionRequestReason()).isEqualTo("request reason");
        assertThat(projection.getDecisionOutboxResolutionApprovalReason()).isIn("approval one", "approval two");
        assertThat(projection.getDecisionOutboxResolutionRequestedAt()).isEqualTo(persisted.getResolutionRequestedAt());
        assertThat(projection.getDecisionOutboxResolutionApprovedAt()).isEqualTo(persisted.getResolutionApprovedAt());
        assertThat(persisted.getResolutionRequestedAt()).isEqualTo(requesterTime);
        assertThat(persisted.getResolutionApprovedAt()).isAfter(persisted.getResolutionRequestedAt());
        assertThat(persisted.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
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

    @Test
    void delayedPublisherProjectionCannotEraseNewerDualControlRequestAndRecoveryRepairsDivergence() {
        TransactionalOutboxRecordDocument original = confirmationUnknownRecord("event-delayed-publisher");
        actualRepository.save(original);
        saveAlertProjection(original);
        TransactionalOutboxRecordDocument delayedPublisherSnapshot = actualRepository
                .findById(original.getEventId())
                .orElseThrow();
        OutboxConfirmationResolutionMutationHandler handler = new OutboxConfirmationResolutionMutationHandler(
                actualRepository,
                mongoTemplate,
                true,
                true
        );

        TransactionalOutboxRecordDocument requested = handler.resolve(
                original.getEventId(),
                resolution(OutboxConfirmationResolution.PUBLISHED, "request reason"),
                "requester"
        );
        OutboxPublisherCoordinator delayedPublisher = new OutboxPublisherCoordinator(
                mock(FraudDecisionEventPublisher.class),
                mongoTemplate,
                mock(AlertServiceMetrics.class),
                Duration.ofMinutes(1),
                5
        );

        delayedPublisher.updateAlertProjection(
                delayedPublisherSnapshot,
                "PUBLISH_CONFIRMATION_UNKNOWN",
                delayedPublisherSnapshot.getLastError(),
                null
        );

        AlertDocument afterDelayedProjection = mongoTemplate.findById("alert-1", AlertDocument.class);
        TransactionalOutboxRecordDocument authoritative = actualRepository
                .findById(original.getEventId())
                .orElseThrow();
        assertThat(requested.getProjectionRevision()).isEqualTo(2L);
        assertThat(authoritative.isResolutionPending()).isTrue();
        assertThat(authoritative.getProjectionRevision()).isEqualTo(2L);
        assertThat(afterDelayedProjection).isNotNull();
        assertThat(afterDelayedProjection.getDecisionOutboxProjectionRevision()).isEqualTo(2L);
        assertThat(afterDelayedProjection.getDecisionOutboxResolutionRequestId())
                .isEqualTo(requested.getResolutionRequestId());
        assertThat(afterDelayedProjection.getDecisionOutboxResolutionRequestedBy()).isEqualTo("requester");
        assertThat(afterDelayedProjection.getDecisionOutboxResolutionRequestReason()).isEqualTo("request reason");
        assertThat(afterDelayedProjection.getDecisionOutboxResolutionEvidenceReference())
                .isEqualTo(requested.getResolutionEvidenceReference());

        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is("alert-1")),
                new Update()
                        .set("decisionOutboxProjectionRevision", 1L)
                        .unset("decisionOutboxResolutionPending")
                        .unset("decisionOutboxResolutionRequestId")
                        .unset("decisionOutboxResolutionRequestedBy")
                        .unset("decisionOutboxResolutionRequestReason")
                        .unset("decisionOutboxResolutionEvidenceReference"),
                AlertDocument.class
        );
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(original.getEventId())),
                new Update()
                        .set("projection_mismatch", true)
                        .set("projection_mismatch_reason", "SIMULATED_RESTART_DIVERGENCE")
                        .set("updated_at", Instant.now()),
                TransactionalOutboxRecordDocument.class
        );

        OutboxRecoveryRunResponse recovery = service(actualRepository).recoverNow();
        AlertDocument repaired = mongoTemplate.findById("alert-1", AlertDocument.class);

        assertThat(recovery.projectionRepaired()).isEqualTo(1);
        assertThat(repaired).isNotNull();
        assertThat(repaired.getDecisionOutboxProjectionRevision()).isEqualTo(2L);
        assertThat(repaired.getDecisionOutboxResolutionRequestId()).isEqualTo(requested.getResolutionRequestId());
        assertThat(repaired.getDecisionOutboxResolutionRequestedBy()).isEqualTo("requester");
    }

    @Test
    void equalRevisionAndStatusStillRepairsCompletePendingIntentProjection() {
        TransactionalOutboxRecordDocument source = confirmationUnknownRecord("event-equal-revision-pending");
        source.setProjectionRevision(5L);
        source.setProjectionReconcileAfter(Instant.now().minusSeconds(1));
        source.setResolutionPending(true);
        source.setResolutionControlMode("DUAL_CONTROL_REQUESTED");
        source.setResolutionRequestId("request-authoritative");
        source.setResolutionProposedOutcome("PUBLISHED");
        source.setResolutionRequestedBy("requester");
        source.setResolutionRequestedAt(Instant.parse("2026-09-30T10:05:00Z"));
        source.setResolutionRequestReason("A");
        source.setResolutionEvidenceType("BROKER_OFFSET");
        source.setResolutionEvidenceReference("partition=0,offset=42");
        source.setResolutionEvidenceVerifiedAt(Instant.parse("2026-09-30T10:05:01Z"));
        source.setResolutionEvidenceVerifiedBy("request-verifier");
        source.setResolutionEvidenceFingerprint("request-fingerprint-authoritative");
        actualRepository.save(source);

        AlertDocument alert = new AlertDocument();
        alert.setAlertId("alert-1");
        alert.setDecisionOutboxEventId(source.getEventId());
        alert.setDecisionOutboxProjectionRevision(5L);
        alert.setDecisionOutboxStatus("PUBLISH_CONFIRMATION_UNKNOWN");
        alert.setDecisionOutboxResolutionPending(false);
        alert.setDecisionOutboxResolutionRequestId("request-corrupted");
        alert.setDecisionOutboxResolutionRequestReason("B");
        alert.setDecisionOutboxResolutionEvidenceFingerprint("request-fingerprint-stale");
        mongoTemplate.save(alert);

        OutboxRecoveryRunResponse response = service(actualRepository).recoverNow();
        AlertDocument repaired = mongoTemplate.findById("alert-1", AlertDocument.class);

        assertThat(response.projectionRepaired()).isOne();
        assertThat(repaired).isNotNull();
        assertThat(repaired.getDecisionOutboxProjectionRevision()).isEqualTo(5L);
        assertThat(repaired.getDecisionOutboxStatus()).isEqualTo("PUBLISH_CONFIRMATION_UNKNOWN");
        assertThat(repaired.isDecisionOutboxResolutionPending()).isTrue();
        assertThat(repaired.getDecisionOutboxResolutionRequestId()).isEqualTo("request-authoritative");
        assertThat(repaired.getDecisionOutboxResolutionRequestReason()).isEqualTo("A");
        assertThat(repaired.getDecisionOutboxResolutionEvidenceFingerprint())
                .isEqualTo("request-fingerprint-authoritative");
    }

    @Test
    void equalRevisionAndPublishedStatusRepairsMissingPublicationProvenance() {
        TransactionalOutboxRecordDocument source = record(
                "event-equal-revision-provenance",
                TransactionalOutboxStatus.PUBLISHED
        );
        source.setProjectionRevision(5L);
        source.setProjectionReconcileAfter(Instant.now().minusSeconds(1));
        source.setPublishedAt(Instant.parse("2026-09-30T10:06:00Z"));
        source.setPublicationConfirmationProvenance(OutboxPublicationConfirmationProvenance.BROKER_ACKNOWLEDGED);
        actualRepository.save(source);

        AlertDocument alert = new AlertDocument();
        alert.setAlertId("alert-1");
        alert.setDecisionOutboxEventId(source.getEventId());
        alert.setDecisionOutboxProjectionRevision(5L);
        alert.setDecisionOutboxStatus("PUBLISHED");
        alert.setDecisionOutboxPublishedAt(source.getPublishedAt());
        mongoTemplate.save(alert);

        OutboxRecoveryRunResponse response = service(actualRepository).recoverNow();
        AlertDocument repaired = mongoTemplate.findById("alert-1", AlertDocument.class);

        assertThat(response.projectionRepaired()).isOne();
        assertThat(repaired).isNotNull();
        assertThat(repaired.getDecisionOutboxPublicationConfirmationProvenance())
                .isEqualTo("BROKER_ACKNOWLEDGED");
    }

    @Test
    void equalRevisionAndStatusRepairsIncompleteDualApprovalEvidence() {
        TransactionalOutboxRecordDocument source = record(
                "event-equal-revision-approval",
                TransactionalOutboxStatus.PUBLISHED
        );
        source.setProjectionRevision(5L);
        source.setProjectionReconcileAfter(Instant.now().minusSeconds(1));
        source.setPublishedAt(Instant.parse("2026-09-30T10:07:00Z"));
        source.setPublicationConfirmationProvenance(
                OutboxPublicationConfirmationProvenance.MANUAL_DUAL_CONTROL_ATTESTED
        );
        source.setResolutionControlMode("DUAL_CONTROL_APPROVED");
        source.setResolutionRequestId("request-approved");
        source.setResolutionProposedOutcome("PUBLISHED");
        source.setResolutionRequestedBy("requester");
        source.setResolutionRequestedAt(Instant.parse("2026-09-30T10:05:00Z"));
        source.setResolutionRequestReason("request reason");
        source.setResolutionEvidenceType("BROKER_OFFSET");
        source.setResolutionEvidenceReference("partition=0,offset=42");
        source.setResolutionEvidenceVerifiedAt(Instant.parse("2026-09-30T10:05:01Z"));
        source.setResolutionEvidenceVerifiedBy("request-verifier");
        source.setResolutionEvidenceFingerprint("request-fingerprint");
        source.setResolutionApprovalReason("approval reason");
        source.setResolutionApprovedBy("approver");
        source.setResolutionApprovedAt(Instant.parse("2026-09-30T10:06:00Z"));
        source.setResolutionApprovalEvidenceType("BROKER_OFFSET");
        source.setResolutionApprovalEvidenceReference("partition=0,offset=43");
        source.setResolutionApprovalEvidenceVerifiedAt(Instant.parse("2026-09-30T10:06:01Z"));
        source.setResolutionApprovalEvidenceVerifiedBy("approval-verifier");
        source.setResolutionApprovalEvidenceFingerprint("approval-fingerprint");
        actualRepository.save(source);

        AlertDocument alert = new AlertDocument();
        alert.setAlertId("alert-1");
        alert.setDecisionOutboxEventId(source.getEventId());
        alert.setDecisionOutboxProjectionRevision(5L);
        alert.setDecisionOutboxStatus("PUBLISHED");
        alert.setDecisionOutboxResolutionRequestId("request-corrupted");
        alert.setDecisionOutboxResolutionEvidenceFingerprint("request-fingerprint-stale");
        alert.setDecisionOutboxResolutionApprovalEvidenceType(null);
        alert.setDecisionOutboxResolutionApprovalEvidenceFingerprint(null);
        mongoTemplate.save(alert);

        OutboxRecoveryRunResponse response = service(actualRepository).recoverNow();
        AlertDocument repaired = mongoTemplate.findById("alert-1", AlertDocument.class);

        assertThat(response.projectionRepaired()).isOne();
        assertThat(repaired).isNotNull();
        assertThat(repaired.getDecisionOutboxResolutionRequestId()).isEqualTo("request-approved");
        assertThat(repaired.getDecisionOutboxResolutionEvidenceFingerprint()).isEqualTo("request-fingerprint");
        assertThat(repaired.getDecisionOutboxResolutionApprovalEvidenceType()).isEqualTo("BROKER_OFFSET");
        assertThat(repaired.getDecisionOutboxResolutionApprovalEvidenceFingerprint())
                .isEqualTo("approval-fingerprint");
        assertThat(repaired.getDecisionOutboxResolutionApprovedBy()).isEqualTo("approver");
    }

    @Test
    void delayedRecoveryAndOlderPublishedProjectionCannotOverwriteManualApproval() {
        TransactionalOutboxRecordDocument original = confirmationUnknownRecord("event-delayed-recovery");
        actualRepository.save(original);
        saveAlertProjection(original);
        OutboxConfirmationResolutionMutationHandler handler = new OutboxConfirmationResolutionMutationHandler(
                actualRepository,
                mongoTemplate,
                true,
                true
        );
        TransactionalOutboxRecordDocument requested = handler.resolve(
                original.getEventId(),
                resolution(OutboxConfirmationResolution.PUBLISHED, "request reason"),
                "requester"
        );
        TransactionalOutboxRecordDocument delayedRecoverySnapshot = actualRepository
                .findById(original.getEventId())
                .orElseThrow();
        TransactionalOutboxRecordDocument approved = handler.resolve(
                original.getEventId(),
                resolution(
                        OutboxConfirmationResolution.PUBLISHED,
                        requested.getResolutionRequestId(),
                        "approval reason"
                ),
                "approver"
        );

        long delayedRecoveryMatches = mongoTemplate.updateFirst(
                OutboxAlertProjectionPolicy.recovery(delayedRecoverySnapshot).target("alert-1"),
                OutboxAlertProjectionPolicy.recovery(delayedRecoverySnapshot).update(),
                AlertDocument.class
        ).getMatchedCount();
        delayedRecoverySnapshot.setStatus(TransactionalOutboxStatus.PUBLISHED);
        delayedRecoverySnapshot.setPublishedAt(Instant.parse("2026-09-30T10:03:00Z"));
        long olderPublishedMatches = mongoTemplate.updateFirst(
                OutboxAlertProjectionPolicy.recovery(delayedRecoverySnapshot).target("alert-1"),
                OutboxAlertProjectionPolicy.recovery(delayedRecoverySnapshot).update(),
                AlertDocument.class
        ).getMatchedCount();

        AlertDocument projection = mongoTemplate.findById("alert-1", AlertDocument.class);
        assertThat(delayedRecoveryMatches).isZero();
        assertThat(olderPublishedMatches).isZero();
        assertThat(projection).isNotNull();
        assertThat(projection.getDecisionOutboxProjectionRevision()).isEqualTo(approved.getProjectionRevision());
        assertThat(projection.getDecisionOutboxResolutionApprovedBy()).isEqualTo("approver");
        assertThat(projection.getDecisionOutboxResolutionApprovalReason()).isEqualTo("approval reason");
    }

    @Test
    void concurrentSameStatusAndAttemptsProjectionsConvergeOnHighestRevision() throws Exception {
        TransactionalOutboxRecordDocument older = confirmationUnknownRecord("event-concurrent-projection");
        TransactionalOutboxRecordDocument newer = confirmationUnknownRecord("event-concurrent-projection");
        newer.setProjectionRevision(2L);
        newer.setResolutionPending(true);
        newer.setResolutionRequestId("request-newer");
        newer.setResolutionProposedOutcome("PUBLISHED");
        newer.setResolutionRequestedBy("requester");
        newer.setResolutionRequestedAt(Instant.parse("2026-09-30T10:04:00Z"));
        newer.setResolutionRequestReason("newer request");
        newer.setResolutionEvidenceReference("topic=fraud-decisions,partition=0,offset=42");
        saveAlertProjection(older);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is("alert-1")),
                new Update().set("decisionOutboxProjectionRevision", 0L),
                AlertDocument.class
        );

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Long> olderResult = executor.submit(() -> writeProjection(older));
            Future<Long> newerResult = executor.submit(() -> writeProjection(newer));
            assertThat(olderResult.get() + newerResult.get()).isBetween(1L, 2L);
        }

        AlertDocument projection = mongoTemplate.findById("alert-1", AlertDocument.class);
        assertThat(projection).isNotNull();
        assertThat(projection.getDecisionOutboxProjectionRevision()).isEqualTo(2L);
        assertThat(projection.getDecisionOutboxStatus()).isEqualTo("PUBLISH_CONFIRMATION_UNKNOWN");
        assertThat(projection.getDecisionOutboxAttempts()).isEqualTo(older.getAttempts());
        assertThat(projection.getDecisionOutboxResolutionRequestId()).isEqualTo("request-newer");
        assertThat(projection.getDecisionOutboxResolutionRequestedBy()).isEqualTo("requester");
    }

    @Test
    void boundedReconciliationMakesProgressWithoutHotLoopingPermanentFailures() {
        Instant createdAt = Instant.now().minusSeconds(1_000);
        TransactionalOutboxRecordDocument activeClaim = mismatchedProjectionRecord(
                "event-active-repair",
                "alert-active-repair",
                createdAt
        );
        actualRepository.save(activeClaim);
        setRepairClaim(activeClaim.getEventId(), "active-worker", Instant.now());
        saveOutdatedProjection(activeClaim);

        TransactionalOutboxRecordDocument expiredClaim = mismatchedProjectionRecord(
                "event-expired-repair",
                "alert-expired-repair",
                createdAt.plusSeconds(1)
        );
        actualRepository.save(expiredClaim);
        setRepairClaim(expiredClaim.getEventId(), "expired-worker", Instant.now().minusSeconds(120));
        saveOutdatedProjection(expiredClaim);

        for (int index = 0; index < 105; index++) {
            TransactionalOutboxRecordDocument permanentFailure = mismatchedProjectionRecord(
                    "event-permanent-" + index,
                    "missing-alert-" + index,
                    createdAt.plusSeconds(index + 2L)
            );
            actualRepository.save(permanentFailure);
        }

        for (int index = 0; index < 5; index++) {
            TransactionalOutboxRecordDocument valid = mismatchedProjectionRecord(
                    "event-valid-" + index,
                    "alert-valid-" + index,
                    createdAt.plusSeconds(index + 200L)
            );
            actualRepository.save(valid);
            saveOutdatedProjection(valid);
        }

        Instant futureEligibility = Instant.now().plusSeconds(3_600).truncatedTo(ChronoUnit.MILLIS);
        TransactionalOutboxRecordDocument futureScheduled = mismatchedProjectionRecord(
                "event-future-scheduled",
                "alert-future-scheduled",
                createdAt.minusSeconds(1)
        );
        futureScheduled.setProjectionReconcileAfter(futureEligibility);
        actualRepository.save(futureScheduled);
        saveOutdatedProjection(futureScheduled);

        OutboxRecoveryRunResponse first = service(actualRepository).recoverNow();

        assertThat(first.projectionRepaired()).isOne();
        assertThat(countScheduledPermanentFailures()).isEqualTo(98L);
        assertThat(repairToken(activeClaim.getEventId())).isEqualTo("active-worker");
        assertThat(actualRepository.findById(futureScheduled.getEventId()).orElseThrow().getProjectionReconcileAfter())
                .isEqualTo(futureEligibility);

        OutboxRecoveryRunResponse second = service(actualRepository).recoverNow();

        assertThat(second.projectionRepaired()).isEqualTo(5);
        assertThat(countScheduledPermanentFailures()).isEqualTo(105L);
        for (int index = 0; index < 5; index++) {
            TransactionalOutboxRecordDocument repaired = actualRepository
                    .findById("event-valid-" + index)
                    .orElseThrow();
            assertThat(repaired.isProjectionMismatch()).isFalse();
            assertThat(repaired.getProjectionReconcileAfter()).isNull();
        }

        Instant retryAt = actualRepository.findById("event-permanent-0").orElseThrow()
                .getProjectionReconcileAfter();
        OutboxRecoveryRunResponse immediateRepeat = service(actualRepository).recoverNow();

        assertThat(immediateRepeat.projectionRepaired()).isZero();
        assertThat(actualRepository.findById("event-permanent-0").orElseThrow().getProjectionReconcileAfter())
                .isEqualTo(retryAt);
        assertThat(actualRepository.findById(futureScheduled.getEventId()).orElseThrow().getProjectionReconcileAfter())
                .isEqualTo(futureEligibility);

        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(activeClaim.getEventId())),
                new Update().set("projection_repair_claimed_at", Instant.now().minusSeconds(120)),
                TransactionalOutboxRecordDocument.class
        );
        OutboxRecoveryRunResponse afterLeaseExpiry = service(actualRepository).recoverNow();

        assertThat(afterLeaseExpiry.projectionRepaired()).isOne();
        TransactionalOutboxRecordDocument reclaimed = actualRepository
                .findById(activeClaim.getEventId())
                .orElseThrow();
        assertThat(repairToken(reclaimed.getEventId())).isNull();
        assertThat(repairClaimedAt(reclaimed.getEventId())).isNull();
    }

    @Test
    void staleProjectionWorkerCannotClearCurrentRepairClaim() {
        TransactionalOutboxRecordDocument source = mismatchedProjectionRecord(
                "event-current-repair-claim",
                "alert-current-repair-claim",
                Instant.now()
        );
        actualRepository.save(source);
        setRepairClaim(source.getEventId(), "current-worker", Instant.now());

        boolean cleared = service(actualRepository).completeProjectionRepair(source, "stale-worker");
        TransactionalOutboxRecordDocument persisted = actualRepository.findById(source.getEventId()).orElseThrow();

        assertThat(cleared).isFalse();
        assertThat(repairToken(persisted.getEventId())).isEqualTo("current-worker");
        assertThat(persisted.isProjectionMismatch()).isTrue();
    }

    @Test
    void reconciliationReclaimsCrashedWorkerAndIsIdempotentWithoutMismatchMarker() {
        TransactionalOutboxRecordDocument source = record(
                "event-crashed-reconciler",
                TransactionalOutboxStatus.PUBLISHED
        );
        source.setProjectionRevision(5L);
        source.setProjectionReconcileAfter(Instant.now().minusSeconds(120));
        actualRepository.save(source);
        AlertDocument alert = new AlertDocument();
        alert.setAlertId("alert-1");
        alert.setDecisionOutboxEventId(source.getEventId());
        alert.setDecisionOutboxProjectionRevision(4L);
        alert.setDecisionOutboxStatus("PUBLISH_CONFIRMATION_UNKNOWN");
        mongoTemplate.save(alert);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(source.getEventId())),
                new Update()
                        .set("projection_repair_token", "abandoned-worker")
                        .set("projection_repair_claimed_at", Instant.now().minusSeconds(120)),
                TransactionalOutboxRecordDocument.class
        );

        OutboxRecoveryRunResponse first = service(actualRepository).recoverNow();
        OutboxRecoveryRunResponse repeated = service(actualRepository).recoverNow();

        TransactionalOutboxRecordDocument reconciled = actualRepository.findById(source.getEventId()).orElseThrow();
        AlertDocument projection = mongoTemplate.findById("alert-1", AlertDocument.class);
        assertThat(first.projectionRepaired()).isOne();
        assertThat(repeated.projectionRepaired()).isZero();
        assertThat(reconciled.isProjectionMismatch()).isFalse();
        assertThat(reconciled.getProjectionReconcileAfter()).isNull();
        assertThat(projection).isNotNull();
        assertThat(projection.getDecisionOutboxProjectionRevision()).isEqualTo(5L);
        assertThat(projection.getDecisionOutboxStatus()).isEqualTo("PUBLISHED");
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
        alert.setDecisionOutboxEventId(original.getEventId());
        alert.setDecisionOutboxProjectionRevision(original.getProjectionRevision());
        alert.setDecisionOutboxStatus("PENDING");
        Instant newerPublishedAt = Instant.parse("2026-09-30T10:10:00Z");
        mongoTemplate.save(alert);

        TransactionalOutboxRecordRepository intercepted = interceptedRepository();
        doAnswer(invocation -> {
            List<TransactionalOutboxRecordDocument> stale = actualRepository
                    .findTop100ByStatusInAndProjectionMismatchTrueAndProjectionReconcileAfterIsNullOrderByCreatedAtAsc(
                            List.of(status)
                    );
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is("alert-1")),
                    new Update()
                            .set("decisionOutboxStatus", "PUBLISHED")
                            .set("decisionOutboxPublishedAt", newerPublishedAt)
                            .set("decisionOutboxProjectionRevision", original.getProjectionRevision() + 1L)
                            .set("decisionOutboxAttempts", original.getAttempts() + 1),
                    AlertDocument.class
            );
            return stale;
        }).when(intercepted)
                .findTop100ByStatusInAndProjectionMismatchTrueAndProjectionReconcileAfterIsNullOrderByCreatedAtAsc(
                        any()
                );

        OutboxRecoveryRunResponse response = service(intercepted).recoverNow();
        AlertDocument persisted = mongoTemplate.findById("alert-1", AlertDocument.class);
        TransactionalOutboxRecordDocument persistedOutbox = actualRepository.findById(original.getEventId()).orElseThrow();

        assertThat(response.projectionRepaired()).isZero();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getDecisionOutboxStatus()).isEqualTo("PUBLISHED");
        assertThat(persisted.getDecisionOutboxPublishedAt()).isEqualTo(newerPublishedAt);
        assertThat(persistedOutbox.isProjectionMismatch()).isTrue();
        assertThat(persistedOutbox.getProjectionMismatchReason())
                .isEqualTo("ALERT_PROJECTION_NEWER_THAN_SOURCE");
    }

    @SuppressWarnings("unchecked")
    private TransactionalOutboxRecordRepository interceptedRepository() {
        return mock(TransactionalOutboxRecordRepository.class, delegatesTo(actualRepository));
    }

    @SuppressWarnings("unchecked")
    private TransactionalOutboxRecordRepository synchronizeFirstTwoReads(String eventId) {
        TransactionalOutboxRecordRepository intercepted = interceptedRepository();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
            Optional<TransactionalOutboxRecordDocument> result = actualRepository.findById(eventId);
            if (reads.incrementAndGet() <= 2) {
                barrier.await();
            }
            return result;
        }).when(intercepted).findById(eventId);
        return intercepted;
    }

    private List<Attempt> runConcurrently(Callable<Attempt> first, Callable<Attempt> second) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Attempt> firstResult = executor.submit(first);
            Future<Attempt> secondResult = executor.submit(second);
            return List.of(firstResult.get(), secondResult.get());
        }
    }

    private long writeProjection(TransactionalOutboxRecordDocument record) {
        OutboxAlertProjectionPolicy.Projection projection = OutboxAlertProjectionPolicy.recovery(record);
        return mongoTemplate.updateFirst(
                projection.target(record.getResourceId()),
                projection.update(),
                AlertDocument.class
        ).getMatchedCount();
    }

    private Attempt attempt(Callable<TransactionalOutboxRecordDocument> operation) {
        try {
            return new Attempt(true, operation.call().getStatus());
        } catch (Exception exception) {
            return new Attempt(false, null);
        }
    }

    private OutboxConfirmationResolutionRequest resolution(
            OutboxConfirmationResolution resolution,
            String reason
    ) {
        return resolution(resolution, null, reason);
    }

    private OutboxConfirmationResolutionRequest resolution(
            OutboxConfirmationResolution resolution,
            String pendingRequestId,
            String reason
    ) {
        return new OutboxConfirmationResolutionRequest(
                resolution,
                pendingRequestId,
                reason,
                new ResolutionEvidenceReference(
                        ResolutionEvidenceType.BROKER_OFFSET,
                        "topic=fraud-decisions,partition=0,offset=42",
                        Instant.parse("2026-09-30T10:02:00Z"),
                        "broker-verifier"
                )
        );
    }

    private void saveAlertProjection(TransactionalOutboxRecordDocument record) {
        AlertDocument alert = new AlertDocument();
        alert.setAlertId("alert-1");
        alert.setDecisionOutboxEventId(record.getEventId());
        alert.setDecisionOutboxProjectionRevision(record.getProjectionRevision());
        alert.setDecisionOutboxStatus("PUBLISH_CONFIRMATION_UNKNOWN");
        alert.setDecisionOutboxAttempts(1);
        mongoTemplate.save(alert);
    }

    private TransactionalOutboxRecordDocument mismatchedProjectionRecord(
            String eventId,
            String alertId,
            Instant createdAt
    ) {
        TransactionalOutboxRecordDocument record = record(eventId, TransactionalOutboxStatus.PUBLISHED);
        record.setResourceId(alertId);
        record.setProjectionMismatch(true);
        record.setProjectionMismatchReason("ALERT_PROJECTION_UPDATE_FAILED");
        record.setCreatedAt(createdAt);
        record.setUpdatedAt(createdAt);
        return record;
    }

    private void saveOutdatedProjection(TransactionalOutboxRecordDocument source) {
        AlertDocument alert = new AlertDocument();
        alert.setAlertId(source.getResourceId());
        alert.setDecisionOutboxEventId(source.getEventId());
        alert.setDecisionOutboxProjectionRevision(source.getProjectionRevision() - 1L);
        alert.setDecisionOutboxStatus("PENDING");
        mongoTemplate.save(alert);
    }

    private long countScheduledPermanentFailures() {
        long scheduled = 0;
        for (int index = 0; index < 105; index++) {
            if (actualRepository.findById("event-permanent-" + index).orElseThrow()
                    .getProjectionReconcileAfter() != null) {
                scheduled++;
            }
        }
        return scheduled;
    }

    private void setRepairClaim(String eventId, String token, Instant claimedAt) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(eventId)),
                new Update()
                        .set("projection_repair_token", token)
                        .set("projection_repair_claimed_at", claimedAt),
                TransactionalOutboxRecordDocument.class
        );
    }

    private String repairToken(String eventId) {
        Document document = rawOutbox(eventId);
        return document == null ? null : document.getString("projection_repair_token");
    }

    private Object repairClaimedAt(String eventId) {
        Document document = rawOutbox(eventId);
        return document == null ? null : document.get("projection_repair_claimed_at");
    }

    private Document rawOutbox(String eventId) {
        return mongoTemplate.getCollection("transactional_outbox_records")
                .find(new Document("_id", eventId))
                .first();
    }

    private TransactionalOutboxRecordDocument confirmationUnknownRecord(String eventId) {
        TransactionalOutboxRecordDocument record = record(
                eventId,
                TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN
        );
        record.setLeaseOwner(null);
        record.setLeaseExpiresAt(null);
        record.setLastError("OUTBOX_PUBLISH_CONFIRMATION_FAILED");
        return record;
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
        record.setProjectionRevision(1L);
        record.setLeaseOwner("old-owner");
        record.setLeaseExpiresAt(Instant.now().minusSeconds(600));
        record.setCreatedAt(Instant.parse("2026-09-30T10:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-09-30T10:00:00Z"));
        return record;
    }

    private record Attempt(boolean success, TransactionalOutboxStatus status) {
    }
}
