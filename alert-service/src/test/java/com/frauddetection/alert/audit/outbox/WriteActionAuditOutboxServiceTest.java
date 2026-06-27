package com.frauddetection.alert.audit.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WriteActionAuditOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-26T10:00:00Z");

    private final WriteActionAuditOutboxRepository repository = mock(WriteActionAuditOutboxRepository.class);
    private final WriteActionAuditOutboxService service = new WriteActionAuditOutboxService(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void createsPendingOutboxRecordWithBoundedMetadata() {
        when(repository.findByIdempotencyKey("RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:ffb-1")).thenReturn(Optional.empty());
        when(repository.save(any(WriteActionAuditOutboxRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WriteActionAuditOutboxRecord record = service.createPendingAudit(
                "RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:ffb-1",
                AuditAction.RECORD_FRAUD_FEEDBACK,
                AuditResourceType.FRAUD_FEEDBACK,
                "ffb-1",
                "corr-1",
                "analyst-1",
                AuditOutcome.SUCCESS,
                metadata()
        );

        assertThat(record.getOutboxId()).startsWith("wao-");
        assertThat(record.getIdempotencyKey()).isEqualTo("RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:ffb-1");
        assertThat(record.getContractVersion()).isEqualTo("write-action-audit-outbox-v1");
        assertThat(record.getStatus()).isEqualTo(WriteActionAuditOutboxStatus.PENDING);
        assertThat(record.getAttemptCount()).isZero();
        assertThat(record.getMaxAttempts()).isEqualTo(5);
        assertThat(record.getCreatedAt()).isEqualTo(NOW);
        assertThat(record.getMetadataSummary().filtersSummary())
                .contains("transactionId=txn-1", "feedbackLabel=CONFIRMED_FRAUD", "status=RECORDED")
                .doesNotContain("Customer confirmed fraud", "rawMlRequest", "rawFeatureVector", "rawEvidence", "token", "secret");
    }

    @Test
    void duplicateIdempotencyKeyReturnsExistingRecordWithoutCreatingDuplicate() {
        WriteActionAuditOutboxRecord existing = existing();
        when(repository.findByIdempotencyKey("RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:ffb-1")).thenReturn(Optional.of(existing));

        WriteActionAuditOutboxRecord record = service.createPendingAudit(
                "RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:ffb-1",
                AuditAction.RECORD_FRAUD_FEEDBACK,
                AuditResourceType.FRAUD_FEEDBACK,
                "ffb-1",
                "corr-1",
                "analyst-1",
                AuditOutcome.SUCCESS,
                metadata()
        );

        assertThat(record).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateIdempotencyKeyWithDifferentActionThrowsConflict() {
        WriteActionAuditOutboxRecord existing = existing();
        existing.setAction(AuditAction.SUBMIT_ENGINE_INTELLIGENCE_FEEDBACK);
        assertIdempotencyConflict(existing);
    }

    @Test
    void duplicateIdempotencyKeyWithDifferentResourceTypeThrowsConflict() {
        WriteActionAuditOutboxRecord existing = existing();
        existing.setResourceType(AuditResourceType.ALERT);
        assertIdempotencyConflict(existing);
    }

    @Test
    void duplicateIdempotencyKeyWithDifferentResourceIdThrowsConflict() {
        WriteActionAuditOutboxRecord existing = existing();
        existing.setResourceId("ffb-2");
        assertIdempotencyConflict(existing);
    }

    @Test
    void duplicateIdempotencyKeyWithDifferentCorrelationIdThrowsConflict() {
        WriteActionAuditOutboxRecord existing = existing();
        existing.setCorrelationId("corr-2");
        assertIdempotencyConflict(existing);
    }

    @Test
    void duplicateIdempotencyKeyWithDifferentActorThrowsConflict() {
        WriteActionAuditOutboxRecord existing = existing();
        existing.setActor("analyst-2");
        assertIdempotencyConflict(existing);
    }

    @Test
    void duplicateIdempotencyKeyWithDifferentOutcomeThrowsConflict() {
        WriteActionAuditOutboxRecord existing = existing();
        existing.setOutcome(AuditOutcome.FAILED);
        assertIdempotencyConflict(existing);
    }

    @Test
    void rejectsUnsafeMetadataBeforePersistence() {
        AuditEventMetadataSummary unsafe = new AuditEventMetadataSummary(
                "corr-1",
                null,
                "alert-service",
                "fraud-feedback-v1",
                null,
                null,
                "POST /api/v1/transactions/scored/{transactionId}/feedback",
                "notes=rawEvidence token",
                1
        );

        assertThatThrownBy(() -> service.createPendingAudit(
                "RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:ffb-1",
                AuditAction.RECORD_FRAUD_FEEDBACK,
                AuditResourceType.FRAUD_FEEDBACK,
                "ffb-1",
                "corr-1",
                "analyst-1",
                AuditOutcome.SUCCESS,
                unsafe
        ))
                .isInstanceOf(WriteActionAuditOutboxException.class)
                .hasMessage("WRITE_ACTION_AUDIT_OUTBOX_METADATA_UNSAFE");

        verify(repository, never()).save(any());
    }

    @Test
    void boundsIdentifiersBeforePersistence() {
        when(repository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(repository.save(any(WriteActionAuditOutboxRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createPendingAudit(
                "x".repeat(250),
                AuditAction.RECORD_FRAUD_FEEDBACK,
                AuditResourceType.FRAUD_FEEDBACK,
                "r".repeat(250),
                "c".repeat(250),
                "a".repeat(250),
                AuditOutcome.SUCCESS,
                metadata()
        );

        ArgumentCaptor<WriteActionAuditOutboxRecord> record = ArgumentCaptor.forClass(WriteActionAuditOutboxRecord.class);
        verify(repository).save(record.capture());
        assertThat(record.getValue().getIdempotencyKey()).hasSize(180);
        assertThat(record.getValue().getResourceId()).hasSize(160);
        assertThat(record.getValue().getCorrelationId()).hasSize(120);
        assertThat(record.getValue().getActor()).hasSize(120);
    }

    private AuditEventMetadataSummary metadata() {
        return new AuditEventMetadataSummary(
                "corr-1",
                null,
                "alert-service",
                "fraud-feedback-v1",
                null,
                null,
                "POST /api/v1/transactions/scored/{transactionId}/feedback",
                "transactionId=txn-1;feedbackLabel=CONFIRMED_FRAUD;status=RECORDED",
                1
        );
    }

    private WriteActionAuditOutboxRecord existing() {
        WriteActionAuditOutboxRecord existing = new WriteActionAuditOutboxRecord();
        existing.setOutboxId("wao-existing");
        existing.setIdempotencyKey("RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:ffb-1");
        existing.setAction(AuditAction.RECORD_FRAUD_FEEDBACK);
        existing.setResourceType(AuditResourceType.FRAUD_FEEDBACK);
        existing.setResourceId("ffb-1");
        existing.setCorrelationId("corr-1");
        existing.setActor("analyst-1");
        existing.setOutcome(AuditOutcome.SUCCESS);
        return existing;
    }

    private void assertIdempotencyConflict(WriteActionAuditOutboxRecord existing) {
        when(repository.findByIdempotencyKey("RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:ffb-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createPendingAudit(
                "RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:ffb-1",
                AuditAction.RECORD_FRAUD_FEEDBACK,
                AuditResourceType.FRAUD_FEEDBACK,
                "ffb-1",
                "corr-1",
                "analyst-1",
                AuditOutcome.SUCCESS,
                metadata()
        ))
                .isInstanceOf(WriteActionAuditOutboxException.class)
                .hasMessage("WRITE_ACTION_AUDIT_OUTBOX_IDEMPOTENCY_CONFLICT");

        verify(repository, never()).save(any());
    }
}
