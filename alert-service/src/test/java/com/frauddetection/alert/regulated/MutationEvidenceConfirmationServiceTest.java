package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditExternalAnchorStatus;
import com.frauddetection.alert.audit.AuditFailureCategory;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.external.AuditEventExternalEvidenceStatus;
import com.frauddetection.alert.audit.external.AuditEventPublicationStatusLookup;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MutationEvidenceConfirmationServiceTest {

    @Test
    void shouldTreatZeroAndNegativeLimitAsNoOp() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                false,
                false
        );

        assertThat(service.confirmPendingEvidence(0)).isZero();
        assertThat(service.confirmPendingEvidence(-1)).isZero();

        verify(commandRepository, never()).findTop100ByStateInAndUpdatedAtBefore(any(), any());
        verify(metrics, never()).recordEvidenceConfirmationPending(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldPromoteCommandOnlyAfterSuccessAuditAndPublishedOutbox() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.of(outbox(TransactionalOutboxStatus.PUBLISHED)));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isEqualTo(1);
        ArgumentCaptor<RegulatedMutationCommandDocument> captor = ArgumentCaptor.forClass(RegulatedMutationCommandDocument.class);
        verify(commandRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RegulatedMutationState.EVIDENCE_CONFIRMED);
        assertThat(captor.getValue().getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED);
    }

    @Test
    void shouldDegradeCommittedCommandWhenSuccessAuditIsMissing() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        command.setSuccessAuditRecorded(false);
        command.setSuccessAuditId(null);
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        ArgumentCaptor<RegulatedMutationCommandDocument> captor = ArgumentCaptor.forClass(RegulatedMutationCommandDocument.class);
        verify(commandRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RegulatedMutationState.COMMITTED_DEGRADED);
        assertThat(captor.getValue().getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
        assertThat(captor.getValue().getDegradationReason()).isEqualTo("SUCCESS_AUDIT_MISSING");
    }

    @Test
    void shouldKeepCommandPendingWhenOutboxIsNotPublished() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.of(outbox(TransactionalOutboxStatus.PENDING)));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        verify(commandRepository, never()).save(any());
        verify(metrics).recordEvidenceConfirmationFailed("OUTBOX_NOT_YET_PUBLISHED");
    }

    @Test
    void shouldDegradeCommittedCommandWhenOutboxIsTerminallyFailed() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1"))
                .thenReturn(Optional.of(outbox(TransactionalOutboxStatus.FAILED_TERMINAL)));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        ArgumentCaptor<RegulatedMutationCommandDocument> captor = ArgumentCaptor.forClass(RegulatedMutationCommandDocument.class);
        verify(commandRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RegulatedMutationState.COMMITTED_DEGRADED);
        assertThat(captor.getValue().getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
        assertThat(captor.getValue().getDegradationReason()).isEqualTo("OUTBOX_FAILED_TERMINAL");
    }

    @Test
    void shouldMapEvidenceGatedTerminalEvidenceFailureToFinalizeRecoveryRequired() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        command.setState(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1"))
                .thenReturn(Optional.of(outbox(TransactionalOutboxStatus.FAILED_TERMINAL)));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        verify(commandRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getState() == RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED
                        && saved.getPublicStatus() == SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED
                        && "OUTBOX_FAILED_TERMINAL".equals(saved.getDegradationReason())));
        verify(metrics).recordEvidenceGatedFinalizeRecoveryRequired("OUTBOX_FAILED_TERMINAL");
    }

    @Test
    void shouldMapEvidenceGatedMissingOutboxAfterLocalCommitToFinalizeRecoveryRequired() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        command.setState(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        command.setOutboxEventId("event-1");
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.empty());

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        verify(commandRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getState() == RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED
                        && saved.getPublicStatus() == SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED
                        && "OUTBOX_RECORD_MISSING_AFTER_LOCAL_COMMIT".equals(saved.getDegradationReason())));
        verify(metrics).recordEvidenceGatedFinalizeRecoveryRequired("OUTBOX_RECORD_MISSING_AFTER_LOCAL_COMMIT");
    }

    @Test
    void shouldPromoteEvidenceGatedCommandToFinalizedEvidenceConfirmedOnlyAfterEvidenceDecisionSucceeds() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        command.setState(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1"))
                .thenReturn(Optional.of(outbox(TransactionalOutboxStatus.PUBLISHED)));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isEqualTo(1);
        verify(commandRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getState() == RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED
                        && saved.getPublicStatus() == SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED));
    }

    @Test
    void shouldRepairEvidenceGatedFinalizedVisibleToPendingExternalWhenEvidenceStillPending() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        command.setState(RegulatedMutationState.FINALIZED_VISIBLE);
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1"))
                .thenReturn(Optional.of(outbox(TransactionalOutboxStatus.PENDING)));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        verify(commandRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getState() == RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                        && saved.getPublicStatus() == SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));
        verify(metrics).recordEvidenceGatedFinalizeStuckVisible();
        verify(metrics).recordEvidenceConfirmationFailed("OUTBOX_NOT_YET_PUBLISHED");
    }

    @Test
    void shouldConfirmWhenExternalAnchorIsRequiredAndPublished() {
        Fixture fixture = new Fixture(true, false);
        RegulatedMutationCommandDocument command = committedCommand();
        fixture.pending(command);
        fixture.publishedOutbox();
        fixture.externalEvidence(new AuditEventExternalEvidenceStatus(AuditExternalAnchorStatus.PUBLISHED, null));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isEqualTo(1);
        verify(fixture.commandRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getPublicStatus() == SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED));
    }

    @Test
    void shouldKeepPendingWhenExternalAnchorIsRequiredButMissing() {
        Fixture fixture = new Fixture(true, false);
        RegulatedMutationCommandDocument command = committedCommand();
        fixture.pending(command);
        fixture.publishedOutbox();
        fixture.externalEvidence(new AuditEventExternalEvidenceStatus(AuditExternalAnchorStatus.UNKNOWN, null));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        verify(fixture.commandRepository, never()).save(any());
        verify(fixture.metrics).recordEvidenceConfirmationFailed("EXTERNAL_ANCHOR_MISSING");
    }

    @Test
    void shouldConfirmWhenSignatureIsRequiredAndValid() {
        Fixture fixture = new Fixture(false, true);
        RegulatedMutationCommandDocument command = committedCommand();
        fixture.pending(command);
        fixture.publishedOutbox();
        fixture.externalEvidence(new AuditEventExternalEvidenceStatus(AuditExternalAnchorStatus.PUBLISHED, "VALID"));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isEqualTo(1);
        verify(fixture.commandRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getPublicStatus() == SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED));
    }

    @Test
    void shouldKeepPendingWhenSignatureIsRequiredButUnavailable() {
        Fixture fixture = new Fixture(false, true);
        RegulatedMutationCommandDocument command = committedCommand();
        fixture.pending(command);
        fixture.publishedOutbox();
        fixture.externalEvidence(new AuditEventExternalEvidenceStatus(AuditExternalAnchorStatus.PUBLISHED, null));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        verify(fixture.commandRepository, never()).save(any());
        verify(fixture.metrics).recordEvidenceConfirmationFailed("SIGNATURE_UNAVAILABLE");
    }

    @Test
    void shouldDegradeWhenSignatureIsRequiredButInvalid() {
        Fixture fixture = new Fixture(false, true);
        RegulatedMutationCommandDocument command = committedCommand();
        fixture.pending(command);
        fixture.publishedOutbox();
        fixture.externalEvidence(new AuditEventExternalEvidenceStatus(AuditExternalAnchorStatus.PUBLISHED, "INVALID"));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        verify(fixture.commandRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getState() == RegulatedMutationState.COMMITTED_DEGRADED
                        && saved.getPublicStatus() == SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE
                        && "SIGNATURE_INVALID".equals(saved.getDegradationReason())));
        verify(fixture.metrics).recordEvidenceConfirmationFailed("SIGNATURE_INVALID");
    }

    @Test
    void shouldMapEvidenceGatedInvalidSignatureToFinalizeRecoveryRequired() {
        Fixture fixture = new Fixture(false, true);
        RegulatedMutationCommandDocument command = committedCommand();
        command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        command.setState(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        fixture.pending(command);
        fixture.publishedOutbox();
        fixture.externalEvidence(new AuditEventExternalEvidenceStatus(AuditExternalAnchorStatus.PUBLISHED, "INVALID"));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        verify(fixture.commandRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getState() == RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED
                        && saved.getPublicStatus() == SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED
                        && "SIGNATURE_INVALID".equals(saved.getDegradationReason())));
        verify(fixture.metrics).recordEvidenceGatedFinalizeRecoveryRequired("SIGNATURE_INVALID");
    }

    private RegulatedMutationCommandDocument committedCommand() {
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setId("command-1");
        command.setState(RegulatedMutationState.EVIDENCE_PENDING);
        command.setLocalCommitMarker("LOCAL_COMMITTED");
        command.setLocalCommittedAt(Instant.parse("2026-05-02T10:00:00Z"));
        command.setSuccessAuditRecorded(true);
        command.setSuccessAuditId("audit-success-1");
        command.setUpdatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        return command;
    }

    private TransactionalOutboxRecordDocument outbox(TransactionalOutboxStatus status) {
        TransactionalOutboxRecordDocument document = new TransactionalOutboxRecordDocument();
        document.setEventId("event-1");
        document.setMutationCommandId("command-1");
        document.setStatus(status);
        document.setCreatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        return document;
    }

    private AuditEventDocument auditEvent() {
        return new AuditEventDocument(
                "audit-success-1",
                AuditAction.SUBMIT_ANALYST_DECISION,
                "principal-7",
                "principal-7",
                List.of("FRAUD_OPS_ADMIN"),
                "HUMAN",
                List.of("decision:write"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                Instant.parse("2026-05-02T10:00:00Z"),
                "corr-1",
                "request-1",
                "alert-service",
                "source_service:alert-service",
                7L,
                AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE,
                null,
                null,
                "previous",
                "hash",
                "SHA-256",
                "1.0"
        );
    }

    private final class Fixture {
        private final RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        private final TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
        private final AuditEventPublicationStatusLookup publicationStatusLookup = mock(AuditEventPublicationStatusLookup.class);
        private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final MutationEvidenceConfirmationService service;

        private Fixture(boolean externalAnchorRequired, boolean signatureRequired) {
            this.service = new MutationEvidenceConfirmationService(
                    commandRepository,
                    outboxRepository,
                    auditEventRepository,
                    publicationStatusLookup,
                    mongoTemplate,
                    metrics,
                    externalAnchorRequired,
                    signatureRequired
            );
        }

        private void pending(RegulatedMutationCommandDocument command) {
            when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        }

        private void publishedOutbox() {
            when(outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.of(outbox(TransactionalOutboxStatus.PUBLISHED)));
        }

        private void externalEvidence(AuditEventExternalEvidenceStatus status) {
            AuditEventDocument auditEvent = auditEvent();
            when(auditEventRepository.findByAuditId("audit-success-1")).thenReturn(Optional.of(auditEvent));
            when(publicationStatusLookup.evidenceStatusesByAuditEventId(List.of(auditEvent)))
                    .thenReturn(java.util.Map.of("audit-success-1", status));
        }
    }
}
