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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
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
                mock(RegulatedMutationFencedCommandWriter.class),
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
                mock(RegulatedMutationFencedCommandWriter.class),
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.of(outbox(TransactionalOutboxStatus.PUBLISHED)));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isEqualTo(1);
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
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
                mock(RegulatedMutationFencedCommandWriter.class),
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        command.setSuccessAuditRecorded(false);
        command.setSuccessAuditId(null);
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getDegradationReason()).isEqualTo("SUCCESS_AUDIT_MISSING");
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
                mock(RegulatedMutationFencedCommandWriter.class),
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.of(outbox(TransactionalOutboxStatus.PENDING)));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
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
                mock(RegulatedMutationFencedCommandWriter.class),
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1"))
                .thenReturn(Optional.of(outbox(TransactionalOutboxStatus.FAILED_TERMINAL)));

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getDegradationReason()).isEqualTo("OUTBOX_FAILED_TERMINAL");
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
                mock(RegulatedMutationFencedCommandWriter.class),
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
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getDegradationReason()).isEqualTo("OUTBOX_FAILED_TERMINAL");
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
                mock(RegulatedMutationFencedCommandWriter.class),
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
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getDegradationReason()).isEqualTo("OUTBOX_RECORD_MISSING_AFTER_LOCAL_COMMIT");
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
                mock(RegulatedMutationFencedCommandWriter.class),
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
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
    }

    @Test
    void shouldConfirmCanonicalOperationThatDoesNotRequireTransactionalOutbox() {
        Fixture fixture = new Fixture(false, false);
        RegulatedMutationCommandDocument command = committedCommand();
        command.setResourceId("case-1");
        command.setResourceType(AuditResourceType.FRAUD_CASE.name());
        command.setAction(AuditAction.UPDATE_FRAUD_CASE.name());
        fixture.pending(command);

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isEqualTo(1);
        verify(fixture.outboxRepository, never()).findByMutationCommandId(any());
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED);
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
                mock(RegulatedMutationFencedCommandWriter.class),
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
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        verify(metrics).recordEvidenceGatedFinalizeStuckVisible();
        verify(metrics).recordEvidenceConfirmationFailed("OUTBOX_NOT_YET_PUBLISHED");
    }

    @Test
    void shouldKeepNewerDurableStateWhenConfirmationCandidateLosesCompareAndSet() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        RegulatedMutationFencedCommandWriter fencedWriter = mock(RegulatedMutationFencedCommandWriter.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                fencedWriter,
                false,
                false
        );
        RegulatedMutationCommandDocument command = committedCommand();
        when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(command));
        when(outboxRepository.findByMutationCommandId("command-1"))
                .thenReturn(Optional.of(outbox(TransactionalOutboxStatus.PUBLISHED)));
        doThrow(new RegulatedMutationRecoveryWriteConflictException(command.getId()))
                .when(fencedWriter)
                .recoveryTransition(any(), any(), any(), any(), any());

        int promoted = service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
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
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
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
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
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
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
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
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
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
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getDegradationReason()).isEqualTo("SIGNATURE_INVALID");
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
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getDegradationReason()).isEqualTo("SIGNATURE_INVALID");
        verify(fixture.metrics).recordEvidenceGatedFinalizeRecoveryRequired("SIGNATURE_INVALID");
    }

    private RegulatedMutationCommandDocument committedCommand() {
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setId("command-1");
        command.setResourceId("alert-1");
        command.setResourceType(AuditResourceType.ALERT.name());
        command.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        command.setState(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
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
        private final RegulatedMutationFencedCommandWriter fencedCommandWriter =
                mock(RegulatedMutationFencedCommandWriter.class);
        private final MutationEvidenceConfirmationService service;

        private Fixture(boolean externalAnchorRequired, boolean signatureRequired) {
            this.service = new MutationEvidenceConfirmationService(
                    commandRepository,
                    outboxRepository,
                    auditEventRepository,
                    publicationStatusLookup,
                    mongoTemplate,
                    metrics,
                    fencedCommandWriter,
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
