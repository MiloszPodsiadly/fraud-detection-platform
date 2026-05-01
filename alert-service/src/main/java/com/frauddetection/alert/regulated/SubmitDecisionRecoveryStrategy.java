package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SubmitDecisionRecoveryStrategy implements RegulatedMutationRecoveryStrategy {

    private static final String BUSINESS_STATE_NOT_RECONSTRUCTABLE = "BUSINESS_STATE_NOT_RECONSTRUCTABLE";

    private final AlertRepository alertRepository;

    public SubmitDecisionRecoveryStrategy(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Override
    public boolean supports(AuditAction action, AuditResourceType resourceType) {
        return action == AuditAction.SUBMIT_ANALYST_DECISION && resourceType == AuditResourceType.ALERT;
    }

    @Override
    public Optional<RegulatedMutationResponseSnapshot> reconstructSnapshot(RegulatedMutationCommandDocument command) {
        return alertRepository.findById(command.getResourceId())
                .filter(this::hasCommittedDecision)
                .map(alert -> new RegulatedMutationResponseSnapshot(
                        alert.getAlertId(),
                        alert.getAnalystDecision(),
                        alert.getAlertStatus() == null ? AlertStatus.RESOLVED : alert.getAlertStatus(),
                        alert.getDecisionOutboxEvent().eventId(),
                        alert.getDecidedAt(),
                        SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
                ));
    }

    @Override
    public RecoveryValidationResult validateBusinessState(RegulatedMutationCommandDocument command) {
        return reconstructSnapshot(command).isPresent()
                ? RecoveryValidationResult.accepted()
                : RecoveryValidationResult.recoveryRequired(BUSINESS_STATE_NOT_RECONSTRUCTABLE);
    }

    private boolean hasCommittedDecision(AlertDocument alert) {
        return alert.getAnalystDecision() != null
                && alert.getDecidedAt() != null
                && alert.getDecisionOutboxEvent() != null
                && alert.getDecisionOutboxStatus() != null;
    }
}
