package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
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
        verify(metrics).recordEvidenceConfirmationFailed("OUTBOX_NOT_PUBLISHED");
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
}
