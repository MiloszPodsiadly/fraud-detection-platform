package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Component
public class RegulatedMutationDurableLocalFinalizationProof {

    static final String LOCAL_COMMIT_MARKER = "EVIDENCE_GATED_FINALIZED";

    private final AuditEventRepository auditEventRepository;
    private final TransactionalOutboxRecordRepository outboxRepository;

    public RegulatedMutationDurableLocalFinalizationProof(
            AuditEventRepository auditEventRepository,
            TransactionalOutboxRecordRepository outboxRepository
    ) {
        this.auditEventRepository = auditEventRepository;
        this.outboxRepository = outboxRepository;
    }

    public DurableLocalFinalizationProofResult verify(RegulatedMutationCommandDocument command) {
        if (command == null
                || command.getMutationModelVersion() != RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1) {
            return invalid("UNSUPPORTED_MUTATION_MODEL");
        }
        if (!StringUtils.hasText(command.getId())
                || !StringUtils.hasText(command.getIdempotencyKey())
                || !StringUtils.hasText(command.getActorId())
                || !StringUtils.hasText(command.getCorrelationId())
                || !StringUtils.hasText(command.getResourceId())) {
            return invalid("COMMAND_IDENTITY_MISSING");
        }
        AuditAction action;
        AuditResourceType resourceType;
        RegulatedMutationDefinition definition;
        try {
            action = AuditAction.valueOf(command.getAction());
            resourceType = AuditResourceType.valueOf(command.getResourceType());
            definition = RegulatedMutationDefinitions.requireSupported(action, resourceType);
        } catch (RuntimeException exception) {
            return invalid("UNSUPPORTED_OPERATION");
        }
        if (!LOCAL_COMMIT_MARKER.equals(command.getLocalCommitMarker())) {
            return invalid("LOCAL_COMMIT_MARKER_MISSING");
        }
        if (command.getLocalCommittedAt() == null) {
            return invalid("LOCAL_COMMITTED_AT_MISSING");
        }
        if (!command.isSuccessAuditRecorded() || !StringUtils.hasText(command.getSuccessAuditId())) {
            return invalid("SUCCESS_AUDIT_MISSING");
        }
        AuditEventDocument audit = auditEventRepository.findByAuditId(command.getSuccessAuditId()).orElse(null);
        if (!validSuccessAudit(command, action, resourceType, audit)) {
            return invalid("SUCCESS_AUDIT_INCONSISTENT");
        }
        if (!definition.requiresTransactionalOutbox()) {
            return DurableLocalFinalizationProofResult.accepted();
        }
        TransactionalOutboxRecordDocument outbox = outboxRepository
                .findByMutationCommandId(command.getId())
                .orElse(null);
        if (!validOutbox(command, resourceType, outbox)) {
            return invalid("TRANSACTIONAL_OUTBOX_PROOF_MISSING");
        }
        return DurableLocalFinalizationProofResult.accepted();
    }

    private boolean validSuccessAudit(
            RegulatedMutationCommandDocument command,
            AuditAction action,
            AuditResourceType resourceType,
            AuditEventDocument audit
    ) {
        String commandKey = StringUtils.hasText(command.getId()) ? command.getId() : command.getIdempotencyKey();
        return audit != null
                && Objects.equals(audit.auditId(), command.getSuccessAuditId())
                && audit.outcome() == AuditOutcome.SUCCESS
                && audit.action() == action
                && audit.resourceType() == resourceType
                && Objects.equals(audit.resourceId(), command.getResourceId())
                && Objects.equals(audit.actorId(), command.getActorId())
                && Objects.equals(audit.correlationId(), command.getCorrelationId())
                && Objects.equals(audit.requestId(), commandKey + ":SUCCESS");
    }

    private boolean validOutbox(
            RegulatedMutationCommandDocument command,
            AuditResourceType resourceType,
            TransactionalOutboxRecordDocument outbox
    ) {
        if (outbox == null
                || !Objects.equals(outbox.getMutationCommandId(), command.getId())
                || !Objects.equals(outbox.getResourceType(), resourceType.name())
                || !Objects.equals(outbox.getResourceId(), command.getResourceId())
                || !"FRAUD_DECISION".equals(outbox.getEventType())
                || !StringUtils.hasText(outbox.getEventId())
                || !Objects.equals(outbox.getEventId(), command.getOutboxEventId())) {
            return false;
        }
        return command.getResponseSnapshot() == null
                || !StringUtils.hasText(command.getResponseSnapshot().decisionEventId())
                || Objects.equals(outbox.getEventId(), command.getResponseSnapshot().decisionEventId());
    }

    private DurableLocalFinalizationProofResult invalid(String reasonCode) {
        return DurableLocalFinalizationProofResult.invalid(reasonCode);
    }
}
