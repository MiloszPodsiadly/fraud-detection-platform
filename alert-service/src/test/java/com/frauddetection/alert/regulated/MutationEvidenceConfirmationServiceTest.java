package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditExternalAnchorStatus;
import com.frauddetection.alert.audit.AuditFailureCategory;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.audit.external.AuditEventExternalEvidenceStatus;
import com.frauddetection.alert.audit.external.AuditEventPublicationStatusLookup;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.OutboxPublicationConfirmationProvenance;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MutationEvidenceConfirmationServiceTest {

    @Test
    void shouldConfirmValidDualControlManualPublicationWithoutCallingItBrokerVerified() {
        Fixture fixture = new Fixture(false, false);
        RegulatedMutationCommandDocument command = committedCommand();
        fixture.pending(command);
        when(fixture.outboxRepository.findByMutationCommandId("command-1"))
                .thenReturn(Optional.of(manualDualControlOutbox()));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isOne();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED);
    }

    @Test
    void shouldFailClosedWhenManualDualControlEvidenceIsIncomplete() {
        Fixture fixture = new Fixture(false, false);
        RegulatedMutationCommandDocument command = committedCommand();
        TransactionalOutboxRecordDocument outbox = manualDualControlOutbox();
        outbox.setResolutionApprovalEvidenceFingerprint(null);
        fixture.pending(command);
        when(fixture.outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.of(outbox));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        verify(fixture.metrics).recordEvidenceConfirmationFailed("MANUAL_PUBLICATION_EVIDENCE_INVALID");
    }

    @Test
    void shouldFailClosedWhenRequestEvidenceWasVerifiedAfterTheRequestTransition() {
        TransactionalOutboxRecordDocument outbox = manualDualControlOutbox();
        Instant impossibleVerifiedAt = outbox.getResolutionRequestedAt().plusMillis(1);
        ResolutionEvidenceReference evidence = new ResolutionEvidenceReference(
                ResolutionEvidenceType.BROKER_OFFSET,
                outbox.getResolutionEvidenceReference(),
                impossibleVerifiedAt,
                outbox.getResolutionEvidenceVerifiedBy()
        );
        outbox.setResolutionEvidenceVerifiedAt(impossibleVerifiedAt);
        outbox.setResolutionEvidenceFingerprint(RegulatedMutationIntentHasher.hash(evidence));

        assertInvalidManualPublicationEvidence(outbox);
    }

    @Test
    void shouldFailClosedWhenApprovalEvidenceWasVerifiedAfterTheApprovalTransition() {
        TransactionalOutboxRecordDocument outbox = manualDualControlOutbox();
        Instant impossibleVerifiedAt = outbox.getResolutionApprovedAt().plusMillis(1);
        ResolutionEvidenceReference evidence = new ResolutionEvidenceReference(
                ResolutionEvidenceType.BROKER_OFFSET,
                outbox.getResolutionApprovalEvidenceReference(),
                impossibleVerifiedAt,
                outbox.getResolutionApprovalEvidenceVerifiedBy()
        );
        outbox.setResolutionApprovalEvidenceVerifiedAt(impossibleVerifiedAt);
        outbox.setResolutionApprovalEvidenceFingerprint(RegulatedMutationIntentHasher.hash(evidence));

        assertInvalidManualPublicationEvidence(outbox);
    }

    @Test
    void shouldKeepSingleControlManualPublicationPending() {
        Fixture fixture = new Fixture(false, false);
        RegulatedMutationCommandDocument command = committedCommand();
        TransactionalOutboxRecordDocument outbox = manualSingleControlOutbox();
        fixture.pending(command);
        when(fixture.outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.of(outbox));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        verify(fixture.metrics).recordEvidenceConfirmationFailed("MANUAL_PUBLICATION_REQUIRES_DUAL_CONTROL");
    }

    @Test
    void shouldFailClosedWhenPublishedRecordHasNoConfirmationProvenance() {
        Fixture fixture = new Fixture(false, false);
        RegulatedMutationCommandDocument command = committedCommand();
        TransactionalOutboxRecordDocument outbox = outbox(TransactionalOutboxStatus.PUBLISHED);
        outbox.setPublicationConfirmationProvenance(null);
        fixture.pending(command);
        when(fixture.outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.of(outbox));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        verify(fixture.metrics).recordEvidenceConfirmationFailed("PUBLICATION_CONFIRMATION_PROVENANCE_MISSING");
    }

    @Test
    void shouldFailClosedWhenPublishedRecordHasNoPublicationTimestamp() {
        Fixture fixture = new Fixture(false, false);
        RegulatedMutationCommandDocument command = committedCommand();
        TransactionalOutboxRecordDocument outbox = outbox(TransactionalOutboxStatus.PUBLISHED);
        outbox.setPublishedAt(null);
        fixture.pending(command);
        when(fixture.outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.of(outbox));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        verify(fixture.metrics).recordEvidenceConfirmationFailed("PUBLICATION_TIMESTAMP_MISSING");
    }

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
                acceptedProof(),
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
                acceptedProof(),
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
                proofReturning(DurableLocalFinalizationProofResult.invalid("SUCCESS_AUDIT_MISSING")),
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
                acceptedProof(),
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
                acceptedProof(),
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
                acceptedProof(),
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
    void shouldMapMissingRequiredOutboxProofToFinalizeRecoveryRequired() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutationEvidenceConfirmationService service = new MutationEvidenceConfirmationService(
                commandRepository,
                outboxRepository,
                metrics,
                mock(RegulatedMutationFencedCommandWriter.class),
                proofReturning(DurableLocalFinalizationProofResult.invalid("TRANSACTIONAL_OUTBOX_PROOF_MISSING")),
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
        assertThat(command.getDegradationReason()).isEqualTo("TRANSACTIONAL_OUTBOX_PROOF_MISSING");
        verify(metrics).recordEvidenceGatedFinalizeRecoveryRequired("TRANSACTIONAL_OUTBOX_PROOF_MISSING");
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
                acceptedProof(),
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
                acceptedProof(),
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

    @Test
    void shouldFailClosedWhenAlertStatusProjectionTargetDoesNotMatch() {
        Fixture fixture = new Fixture(false, false);
        RegulatedMutationCommandDocument command = committedCommand();
        when(fixture.mongoTemplate.updateFirst(any(), any(), eq(com.frauddetection.alert.persistence.AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        when(fixture.mongoTemplate.findById("alert-1", com.frauddetection.alert.persistence.AlertDocument.class))
                .thenReturn(null);

        assertThatThrownBy(() -> fixture.service.updateAlertOperationStatus(
                command,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED
        )).isInstanceOf(RegulatedMutationAlertProjectionException.class);

        verify(fixture.metrics).recordRegulatedMutationAlertStatusProjection(
                "FAILED",
                "TARGET_NOT_FOUND_OR_MISMATCH"
        );
    }

    private RegulatedMutationCommandDocument committedCommand() {
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setId("command-1");
        command.setIdempotencyKey("idem-1");
        command.setActorId("principal-7");
        command.setCorrelationId("corr-1");
        command.setResourceId("alert-1");
        command.setResourceType(AuditResourceType.ALERT.name());
        command.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        command.setRevision(0L);
        command.setState(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        command.setLocalCommitMarker(RegulatedMutationDurableLocalFinalizationProof.LOCAL_COMMIT_MARKER);
        command.setLocalCommittedAt(Instant.parse("2026-05-02T10:00:00Z"));
        command.setSuccessAuditRecorded(true);
        command.setSuccessAuditId("audit-success-1");
        command.setUpdatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        return command;
    }

    private void assertInvalidManualPublicationEvidence(TransactionalOutboxRecordDocument outbox) {
        Fixture fixture = new Fixture(false, false);
        RegulatedMutationCommandDocument command = committedCommand();
        fixture.pending(command);
        when(fixture.outboxRepository.findByMutationCommandId("command-1")).thenReturn(Optional.of(outbox));

        int promoted = fixture.service.confirmPendingEvidence(100);

        assertThat(promoted).isZero();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        verify(fixture.metrics).recordEvidenceConfirmationFailed("MANUAL_PUBLICATION_EVIDENCE_INVALID");
    }

    private TransactionalOutboxRecordDocument outbox(TransactionalOutboxStatus status) {
        TransactionalOutboxRecordDocument document = new TransactionalOutboxRecordDocument();
        document.setEventId("event-1");
        document.setMutationCommandId("command-1");
        document.setStatus(status);
        if (status == TransactionalOutboxStatus.PUBLISHED) {
            document.setPublicationConfirmationProvenance(
                    OutboxPublicationConfirmationProvenance.BROKER_ACKNOWLEDGED
            );
            document.setPublishedAt(Instant.parse("2026-05-02T10:01:00Z"));
        }
        document.setCreatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        return document;
    }

    private TransactionalOutboxRecordDocument manualDualControlOutbox() {
        TransactionalOutboxRecordDocument document = outbox(TransactionalOutboxStatus.PUBLISHED);
        Instant requestedAt = Instant.parse("2026-05-02T10:05:00Z");
        Instant approvedAt = Instant.parse("2026-05-02T10:06:00Z");
        ResolutionEvidenceReference requestEvidence = new ResolutionEvidenceReference(
                ResolutionEvidenceType.BROKER_OFFSET,
                "topic=fraud-decisions,partition=0,offset=42",
                requestedAt,
                "request-verifier"
        );
        ResolutionEvidenceReference approvalEvidence = new ResolutionEvidenceReference(
                ResolutionEvidenceType.BROKER_OFFSET,
                "topic=fraud-decisions,partition=0,offset=42",
                approvedAt,
                "approval-verifier"
        );
        document.setPublicationConfirmationProvenance(
                OutboxPublicationConfirmationProvenance.MANUAL_DUAL_CONTROL_ATTESTED
        );
        document.setResolutionControlMode("DUAL_CONTROL_APPROVED");
        document.setResolutionRequestId("request-1");
        document.setResolutionProposedOutcome("PUBLISHED");
        document.setResolutionRequestedBy("requester");
        document.setResolutionRequestedAt(requestedAt);
        document.setResolutionRequestReason("request reason");
        document.setResolutionEvidenceType(requestEvidence.type().name());
        document.setResolutionEvidenceReference(requestEvidence.reference());
        document.setResolutionEvidenceVerifiedAt(requestEvidence.verifiedAt());
        document.setResolutionEvidenceVerifiedBy(requestEvidence.verifiedBy());
        document.setResolutionEvidenceFingerprint(RegulatedMutationIntentHasher.hash(requestEvidence));
        document.setResolutionApprovedBy("approver");
        document.setResolutionApprovedAt(approvedAt);
        document.setResolutionApprovalReason("approval reason");
        document.setResolutionApprovalEvidenceType(approvalEvidence.type().name());
        document.setResolutionApprovalEvidenceReference(approvalEvidence.reference());
        document.setResolutionApprovalEvidenceVerifiedAt(approvalEvidence.verifiedAt());
        document.setResolutionApprovalEvidenceVerifiedBy(approvalEvidence.verifiedBy());
        document.setResolutionApprovalEvidenceFingerprint(RegulatedMutationIntentHasher.hash(approvalEvidence));
        return document;
    }

    private TransactionalOutboxRecordDocument manualSingleControlOutbox() {
        TransactionalOutboxRecordDocument document = outbox(TransactionalOutboxStatus.PUBLISHED);
        Instant approvedAt = Instant.parse("2026-05-02T10:06:00Z");
        ResolutionEvidenceReference evidence = new ResolutionEvidenceReference(
                ResolutionEvidenceType.BROKER_OFFSET,
                "topic=fraud-decisions,partition=0,offset=42",
                approvedAt.minusSeconds(1),
                "operator-verifier"
        );
        document.setPublicationConfirmationProvenance(
                OutboxPublicationConfirmationProvenance.MANUAL_SINGLE_CONTROL_ATTESTED
        );
        document.setResolutionControlMode("SINGLE_CONTROL_OPERATOR_ATTESTED");
        document.setResolutionProposedOutcome("PUBLISHED");
        document.setResolutionApprovedBy("operator");
        document.setResolutionApprovedAt(approvedAt);
        document.setResolutionApprovalReason("operator reason");
        document.setResolutionEvidenceType(evidence.type().name());
        document.setResolutionEvidenceReference(evidence.reference());
        document.setResolutionEvidenceVerifiedAt(evidence.verifiedAt());
        document.setResolutionEvidenceVerifiedBy(evidence.verifiedBy());
        document.setResolutionEvidenceFingerprint(RegulatedMutationIntentHasher.hash(evidence));
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
        private final RegulatedMutationDurableLocalFinalizationProof durableProof = acceptedProof();
        private final MutationEvidenceConfirmationService service;

        private Fixture(boolean externalAnchorRequired, boolean signatureRequired) {
            when(mongoTemplate.updateFirst(any(), any(), eq(com.frauddetection.alert.persistence.AlertDocument.class)))
                    .thenReturn(UpdateResult.acknowledged(1, 1L, null));
            this.service = new MutationEvidenceConfirmationService(
                    commandRepository,
                    outboxRepository,
                    auditEventRepository,
                    publicationStatusLookup,
                    mongoTemplate,
                    metrics,
                    fencedCommandWriter,
                    durableProof,
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

    private static RegulatedMutationDurableLocalFinalizationProof acceptedProof() {
        return proofReturning(DurableLocalFinalizationProofResult.accepted());
    }

    private static RegulatedMutationDurableLocalFinalizationProof proofReturning(
            DurableLocalFinalizationProofResult result
    ) {
        RegulatedMutationDurableLocalFinalizationProof proof =
                mock(RegulatedMutationDurableLocalFinalizationProof.class);
        when(proof.verify(any())).thenReturn(result);
        return proof;
    }
}
