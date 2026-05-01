package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import org.springframework.stereotype.Service;

@Service
public class RegulatedMutationAuditPhaseService {

    private final AuditEventRepository auditEventRepository;
    private final AuditService auditService;

    public RegulatedMutationAuditPhaseService(AuditEventRepository auditEventRepository, AuditService auditService) {
        this.auditEventRepository = auditEventRepository;
        this.auditService = auditService;
    }

    public String recordPhase(
            RegulatedMutationCommandDocument command,
            AuditAction action,
            AuditResourceType resourceType,
            AuditOutcome outcome,
            String failureReason
    ) {
        String phaseKey = phaseKey(command, phase(outcome));
        return auditEventRepository.findByRequestId(phaseKey)
                .map(AuditEventDocument::auditId)
                .orElseGet(() -> appendPhase(command, action, resourceType, outcome, failureReason, phaseKey));
    }

    public String findPhaseAuditId(RegulatedMutationCommandDocument command, RegulatedMutationAuditPhase phase) {
        return auditEventRepository.findByRequestId(phaseKey(command, phase))
                .map(AuditEventDocument::auditId)
                .orElse(null);
    }

    private String appendPhase(
            RegulatedMutationCommandDocument command,
            AuditAction action,
            AuditResourceType resourceType,
            AuditOutcome outcome,
            String failureReason,
            String phaseKey
    ) {
        auditService.audit(
                action,
                resourceType,
                command.getResourceId(),
                command.getCorrelationId(),
                command.getActorId(),
                outcome,
                failureReason,
                new AuditEventMetadataSummary(command.getCorrelationId(), phaseKey, "alert-service", "1.0", null, failureReason, null, null, null),
                phaseKey
        );
        return auditEventRepository.findByRequestId(phaseKey)
                .map(AuditEventDocument::auditId)
                .orElse(phaseKey);
    }

    private RegulatedMutationAuditPhase phase(AuditOutcome outcome) {
        return switch (outcome) {
            case ATTEMPTED -> RegulatedMutationAuditPhase.ATTEMPTED;
            case SUCCESS -> RegulatedMutationAuditPhase.SUCCESS;
            case FAILED -> RegulatedMutationAuditPhase.FAILED;
            default -> RegulatedMutationAuditPhase.FAILED;
        };
    }

    private String phaseKey(RegulatedMutationCommandDocument command, RegulatedMutationAuditPhase phase) {
        String commandId = command.getId();
        if (commandId == null || commandId.isBlank()) {
            commandId = command.getIdempotencyKey();
        }
        return commandId + ":" + phase.name();
    }
}
