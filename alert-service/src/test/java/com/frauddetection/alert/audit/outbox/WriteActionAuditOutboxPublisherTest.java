package com.frauddetection.alert.audit.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WriteActionAuditOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-06-26T11:00:00Z");
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    private final WriteActionAuditOutboxRepository repository = mock(WriteActionAuditOutboxRepository.class);
    private final WriteActionAuditOutboxClaimStore claimStore = mock(WriteActionAuditOutboxClaimStore.class);
    private final AuditService auditService = mock(AuditService.class);
    private final WriteActionAuditOutboxPublisher publisher = new WriteActionAuditOutboxPublisher(
            repository,
            claimStore,
            auditService,
            Clock.fixed(NOW, ZoneOffset.UTC),
            "publisher-1",
            CLAIM_LEASE
    );

    @BeforeEach
    void allowCasTransitionsByDefault() {
        org.mockito.Mockito.lenient().when(claimStore.markPublished(any(), eq(NOW))).thenReturn(true);
        org.mockito.Mockito.lenient().when(claimStore.markFailed(any(), eq(NOW), any())).thenReturn(true);
    }

    @Test
    void pendingRecordPublishesSuccessfullyAndBecomesPublished() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.PENDING, 0, 5);
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE)).thenReturn(Optional.of(record));

        int published = publisher.publishPending();

        assertThat(published).isEqualTo(1);
        verify(auditService).audit(
                eq(AuditAction.RECORD_FRAUD_FEEDBACK),
                eq(AuditResourceType.FRAUD_FEEDBACK),
                eq("ffb-1"),
                eq("corr-1"),
                eq("analyst-1"),
                eq(AuditOutcome.SUCCESS),
                eq(null),
                any(AuditEventMetadataSummary.class)
        );
        verify(claimStore).markPublished(record, NOW);
        verify(repository, never()).save(any());
    }

    @Test
    void failedRetryableEligibleRecordCanPublishSuccessfully() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.FAILED_RETRYABLE, 1, 5);
        record.setNextAttemptAt(NOW.minusSeconds(1));
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE)).thenReturn(Optional.of(record));

        assertThat(publisher.publishPending()).isEqualTo(1);

        verify(claimStore).markPublished(record, NOW);
    }

    @Test
    void publishedRecordIsSkippedDefensively() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.PUBLISHED, 0, 5);
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));

        assertThat(publisher.publishPending()).isZero();

        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any());
        verify(claimStore, never()).claimForPublishing(any(), any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void freshPublishingRecordClaimFailureDoesNotCallAuditService() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.PUBLISHING, 0, 5);
        record.setClaimExpiresAt(NOW.plusSeconds(60));
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE)).thenReturn(Optional.empty());

        assertThat(publisher.publishPending()).isZero();

        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any());
        verify(claimStore).claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE);
        verify(repository, never()).save(any());
    }

    @Test
    void claimFailureSkipsRecordAndDoesNotCallAuditService() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.PENDING, 0, 5);
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE)).thenReturn(Optional.empty());

        assertThat(publisher.publishPending()).isZero();

        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void transientFailureIncrementsAttemptAndSchedulesBoundedRetry() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.PENDING, 0, 5);
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE)).thenReturn(Optional.of(record));
        doThrow(new IllegalStateException("raw stack trace secret token"))
                .when(auditService).audit(any(), any(), any(), any(), any(), any(), any(), any());

        assertThat(publisher.publishPending()).isZero();

        verify(claimStore).markFailed(record, NOW, "AUDIT_SERVICE_UNAVAILABLE");
        verify(repository, never()).save(any());
    }

    @Test
    void failureAtMaxAttemptsBecomesPermanentAndDoesNotRetryForever() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.FAILED_RETRYABLE, 4, 5);
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE)).thenReturn(Optional.of(record));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).audit(any(), any(), any(), any(), any(), any(), any(), any());

        publisher.publishPending();

        verify(claimStore).markFailed(record, NOW, "AUDIT_SERVICE_UNAVAILABLE");
    }

    @Test
    void batchSizeIsBoundedToFifty() {
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of());

        publisher.publishPending(500);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findPublishable(eq(NOW), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void repeatedRunDoesNotPublishAlreadyPublishedRecordAgain() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.PENDING, 0, 5);
        when(repository.findPublishable(eq(NOW), any()))
                .thenReturn(List.of(record))
                .thenReturn(List.of());
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE)).thenReturn(Optional.of(record));

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(publisher.publishPending()).isZero();

        verify(auditService).audit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void twoPublishAttemptsForSameCandidateCallAuditServiceOnceWhenSecondClaimFails() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.PENDING, 0, 5);
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE))
                .thenReturn(Optional.of(record))
                .thenReturn(Optional.empty());

        assertThat(publisher.publishPending()).isEqualTo(1);
        record.setStatus(WriteActionAuditOutboxStatus.PENDING);
        assertThat(publisher.publishPending()).isZero();

        verify(auditService).audit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void stalePublishingRecordCanBeRecoveredAndPublished() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.PUBLISHING, 1, 5);
        record.setClaimedAt(NOW.minusSeconds(600));
        record.setClaimOwner("dead-publisher");
        record.setClaimExpiresAt(NOW.minusSeconds(1));
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE)).thenReturn(Optional.of(record));

        assertThat(publisher.publishPending()).isEqualTo(1);

        verify(claimStore).markPublished(record, NOW);
        verify(auditService).audit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failureAfterStalePublishingRecoveryIncrementsAttemptAndSchedulesRetry() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.PUBLISHING, 1, 5);
        record.setClaimExpiresAt(NOW.minusSeconds(1));
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE)).thenReturn(Optional.of(record));
        doThrow(new IllegalStateException("secret token stack trace"))
                .when(auditService).audit(any(), any(), any(), any(), any(), any(), any(), any());

        assertThat(publisher.publishPending()).isZero();

        verify(claimStore).markFailed(record, NOW, "AUDIT_SERVICE_UNAVAILABLE");
    }

    @Test
    void publishingAtMaxAttemptsFailureBecomesFailedPermanent() {
        WriteActionAuditOutboxRecord record = record(WriteActionAuditOutboxStatus.PUBLISHING, 4, 5);
        record.setClaimExpiresAt(NOW.minusSeconds(1));
        when(repository.findPublishable(eq(NOW), any())).thenReturn(List.of(record));
        when(claimStore.claimForPublishing("wao-1", NOW, "publisher-1", CLAIM_LEASE)).thenReturn(Optional.of(record));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).audit(any(), any(), any(), any(), any(), any(), any(), any());

        publisher.publishPending();

        verify(claimStore).markFailed(record, NOW, "AUDIT_SERVICE_UNAVAILABLE");
    }

    private WriteActionAuditOutboxRecord record(WriteActionAuditOutboxStatus status, int attemptCount, int maxAttempts) {
        WriteActionAuditOutboxRecord record = new WriteActionAuditOutboxRecord();
        record.setOutboxId("wao-1");
        record.setIdempotencyKey("RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:ffb-1");
        record.setContractVersion("write-action-audit-outbox-v1");
        record.setAction(AuditAction.RECORD_FRAUD_FEEDBACK);
        record.setResourceType(AuditResourceType.FRAUD_FEEDBACK);
        record.setResourceId("ffb-1");
        record.setCorrelationId("corr-1");
        record.setActor("analyst-1");
        record.setOutcome(AuditOutcome.SUCCESS);
        record.setMetadataSummary(new AuditEventMetadataSummary(
                "corr-1",
                null,
                "alert-service",
                "fraud-feedback-v1",
                null,
                null,
                "POST /api/v1/transactions/scored/{transactionId}/feedback",
                "transactionId=txn-1;feedbackLabel=CONFIRMED_FRAUD;status=RECORDED",
                1
        ));
        record.setStatus(status);
        record.setAttemptCount(attemptCount);
        record.setMaxAttempts(maxAttempts);
        record.setCreatedAt(NOW.minusSeconds(60));
        record.setClaimedAt(NOW);
        record.setClaimOwner("publisher-1");
        record.setClaimToken("claim-token-1");
        record.setClaimGeneration(1L);
        record.setClaimExpiresAt(NOW.plus(CLAIM_LEASE));
        return record;
    }
}
