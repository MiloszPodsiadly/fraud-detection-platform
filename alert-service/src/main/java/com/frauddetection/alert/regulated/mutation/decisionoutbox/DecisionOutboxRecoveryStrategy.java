package com.frauddetection.alert.regulated.mutation.decisionoutbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.RecoveryValidationResult;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryStrategy;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DecisionOutboxRecoveryStrategy implements RegulatedMutationRecoveryStrategy {

    private static final String BUSINESS_STATE_NOT_RECONSTRUCTABLE = "BUSINESS_STATE_NOT_RECONSTRUCTABLE";

    private final AlertRepository alertRepository;

    public DecisionOutboxRecoveryStrategy(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Override
    public boolean supports(AuditAction action, AuditResourceType resourceType) {
        return action == AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION && resourceType == AuditResourceType.DECISION_OUTBOX;
    }

    @Override
    public Optional<RegulatedMutationResponseSnapshot> reconstructSnapshot(RegulatedMutationCommandDocument command) {
        return alert(command)
                .map(alert -> new RegulatedMutationResponseSnapshot(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        alert.getAlertId(),
                        eventId(alert),
                        dedupeKey(alert),
                        alert.getDecisionOutboxStatus(),
                        alert.getDecidedAt(),
                        alert.getDecisionOutboxAttempts(),
                        alert.getDecisionOutboxLastAttemptAt(),
                        alert.getDecisionOutboxPublishedAt(),
                        alert.getDecisionOutboxFailureReason(),
                        alert.isDecisionOutboxResolutionPending(),
                        alert.getDecisionOutboxResolutionRequestedAt(),
                        alert.getDecisionOutboxResolutionRequestedBy(),
                        alert.getDecisionOutboxResolutionEvidenceType(),
                        alert.getDecisionOutboxResolutionEvidenceReference(),
                        alert.getDecisionOutboxResolutionEvidenceVerifiedAt(),
                        alert.getDecisionOutboxResolutionEvidenceVerifiedBy(),
                        alert.getDecisionOutboxResolutionApprovedAt(),
                        alert.getDecisionOutboxResolutionApprovedBy(),
                        alert.getDecisionOutboxResolutionApprovalReason()
                ));
    }

    @Override
    public RecoveryValidationResult validateBusinessState(RegulatedMutationCommandDocument command) {
        return alert(command).isPresent()
                ? RecoveryValidationResult.accepted()
                : RecoveryValidationResult.recoveryRequired(BUSINESS_STATE_NOT_RECONSTRUCTABLE);
    }

    private Optional<AlertDocument> alert(RegulatedMutationCommandDocument command) {
        Optional<AlertDocument> byAlertId = alertRepository.findById(command.getResourceId());
        if (byAlertId.isPresent()) {
            return byAlertId;
        }
        return alertRepository.findByDecisionOutboxEventEventId(command.getResourceId());
    }

    private String eventId(AlertDocument alert) {
        return alert.getDecisionOutboxEvent() == null ? null : alert.getDecisionOutboxEvent().eventId();
    }

    private String dedupeKey(AlertDocument alert) {
        return alert.getDecisionOutboxEvent() == null ? null : alert.getDecisionOutboxEvent().dedupeKey();
    }
}
