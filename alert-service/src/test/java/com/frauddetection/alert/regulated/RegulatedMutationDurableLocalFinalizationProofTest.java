package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegulatedMutationDurableLocalFinalizationProofTest {

    private final AuditEventRepository auditRepository = mock(AuditEventRepository.class);
    private final TransactionalOutboxRecordRepository outboxRepository =
            mock(TransactionalOutboxRecordRepository.class);
    private final RegulatedMutationDurableLocalFinalizationProof proof =
            new RegulatedMutationDurableLocalFinalizationProof(auditRepository, outboxRepository);

    @Test
    void acceptsCompleteDurableProofForOperationRequiringOutbox() {
        RegulatedMutationCommandDocument command = command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        AuditEventDocument audit = successAudit(command);
        when(auditRepository.findByAuditId("audit-success")).thenReturn(Optional.of(audit));
        when(outboxRepository.findByMutationCommandId(command.getId())).thenReturn(Optional.of(outbox(command)));

        assertThat(proof.verify(command)).isEqualTo(DurableLocalFinalizationProofResult.accepted());
    }

    @Test
    void rejectsMissingLocalCommitMarkerBeforeConsultingDurableEvidence() {
        RegulatedMutationCommandDocument command = command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        command.setLocalCommitMarker(null);

        assertThat(proof.verify(command))
                .isEqualTo(DurableLocalFinalizationProofResult.invalid("LOCAL_COMMIT_MARKER_MISSING"));
        verify(auditRepository, never()).findByAuditId("audit-success");
    }

    @Test
    void rejectsMissingCommandIdentityBeforeConsultingDurableEvidence() {
        RegulatedMutationCommandDocument command = command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        command.setId(null);

        assertThat(proof.verify(command))
                .isEqualTo(DurableLocalFinalizationProofResult.invalid("COMMAND_IDENTITY_MISSING"));
        verify(auditRepository, never()).findByAuditId("audit-success");
    }

    @Test
    void rejectsMissingSuccessAudit() {
        RegulatedMutationCommandDocument command = command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        command.setSuccessAuditRecorded(false);

        assertThat(proof.verify(command))
                .isEqualTo(DurableLocalFinalizationProofResult.invalid("SUCCESS_AUDIT_MISSING"));
    }

    @Test
    void rejectsAuditWhoseDurableIdentityDoesNotMatchCommand() {
        RegulatedMutationCommandDocument command = command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        AuditEventDocument audit = successAudit(command);
        when(audit.actorId()).thenReturn("different-actor");
        when(auditRepository.findByAuditId("audit-success")).thenReturn(Optional.of(audit));

        assertThat(proof.verify(command))
                .isEqualTo(DurableLocalFinalizationProofResult.invalid("SUCCESS_AUDIT_INCONSISTENT"));
    }

    @Test
    void rejectsMissingRequiredTransactionalOutboxRecord() {
        RegulatedMutationCommandDocument command = command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        AuditEventDocument audit = successAudit(command);
        when(auditRepository.findByAuditId("audit-success")).thenReturn(Optional.of(audit));
        when(outboxRepository.findByMutationCommandId(command.getId())).thenReturn(Optional.empty());

        assertThat(proof.verify(command))
                .isEqualTo(DurableLocalFinalizationProofResult.invalid("TRANSACTIONAL_OUTBOX_PROOF_MISSING"));
    }

    @Test
    void rejectsInconsistentRequiredTransactionalOutboxIdentity() {
        RegulatedMutationCommandDocument command = command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        AuditEventDocument audit = successAudit(command);
        TransactionalOutboxRecordDocument outbox = outbox(command);
        outbox.setEventId("different-event");
        when(auditRepository.findByAuditId("audit-success")).thenReturn(Optional.of(audit));
        when(outboxRepository.findByMutationCommandId(command.getId())).thenReturn(Optional.of(outbox));

        assertThat(proof.verify(command))
                .isEqualTo(DurableLocalFinalizationProofResult.invalid("TRANSACTIONAL_OUTBOX_PROOF_MISSING"));
    }

    @Test
    void doesNotRequireOutboxForOperationWhoseDefinitionDoesNotRequireIt() {
        RegulatedMutationCommandDocument command = command(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE
        );
        command.setOutboxEventId(null);
        AuditEventDocument audit = successAudit(command);
        when(auditRepository.findByAuditId("audit-success")).thenReturn(Optional.of(audit));

        assertThat(proof.verify(command)).isEqualTo(DurableLocalFinalizationProofResult.accepted());
        verify(outboxRepository, never()).findByMutationCommandId(command.getId());
    }

    private RegulatedMutationCommandDocument command(AuditAction action, AuditResourceType resourceType) {
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setId("command-1");
        command.setIdempotencyKey("idem-1");
        command.setActorId("analyst-1");
        command.setCorrelationId("correlation-1");
        command.setResourceId(resourceType == AuditResourceType.ALERT ? "alert-1" : "case-1");
        command.setAction(action.name());
        command.setResourceType(resourceType.name());
        command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        command.setLocalCommitMarker(RegulatedMutationDurableLocalFinalizationProof.LOCAL_COMMIT_MARKER);
        command.setLocalCommittedAt(Instant.parse("2026-09-29T00:00:00Z"));
        command.setSuccessAuditRecorded(true);
        command.setSuccessAuditId("audit-success");
        command.setOutboxEventId("event-1");
        return command;
    }

    private AuditEventDocument successAudit(RegulatedMutationCommandDocument command) {
        AuditEventDocument audit = mock(AuditEventDocument.class);
        when(audit.auditId()).thenReturn(command.getSuccessAuditId());
        when(audit.action()).thenReturn(AuditAction.valueOf(command.getAction()));
        when(audit.resourceType()).thenReturn(AuditResourceType.valueOf(command.getResourceType()));
        when(audit.resourceId()).thenReturn(command.getResourceId());
        when(audit.actorId()).thenReturn(command.getActorId());
        when(audit.correlationId()).thenReturn(command.getCorrelationId());
        when(audit.requestId()).thenReturn(command.getId() + ":SUCCESS");
        when(audit.outcome()).thenReturn(AuditOutcome.SUCCESS);
        return audit;
    }

    private TransactionalOutboxRecordDocument outbox(RegulatedMutationCommandDocument command) {
        TransactionalOutboxRecordDocument outbox = new TransactionalOutboxRecordDocument();
        outbox.setEventId(command.getOutboxEventId());
        outbox.setMutationCommandId(command.getId());
        outbox.setResourceType(command.getResourceType());
        outbox.setResourceId(command.getResourceId());
        outbox.setEventType("FRAUD_DECISION");
        return outbox;
    }
}
