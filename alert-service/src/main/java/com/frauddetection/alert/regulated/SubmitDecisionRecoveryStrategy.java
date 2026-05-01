package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Objects;

@Component
public class SubmitDecisionRecoveryStrategy implements RegulatedMutationRecoveryStrategy {

    private static final String BUSINESS_STATE_NOT_RECONSTRUCTABLE = "BUSINESS_STATE_NOT_RECONSTRUCTABLE";
    private static final String BUSINESS_STATE_INTENT_MISMATCH = "BUSINESS_STATE_INTENT_MISMATCH";

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
        Optional<AlertDocument> alert = alertRepository.findById(command.getResourceId())
                .filter(this::hasCommittedDecision);
        if (alert.isEmpty()) {
            return RecoveryValidationResult.recoveryRequired(BUSINESS_STATE_NOT_RECONSTRUCTABLE);
        }
        return matchesIntent(command, alert.get())
                ? RecoveryValidationResult.accepted()
                : RecoveryValidationResult.recoveryRequired(BUSINESS_STATE_INTENT_MISMATCH);
    }

    private boolean hasCommittedDecision(AlertDocument alert) {
        return alert.getAnalystDecision() != null
                && alert.getDecidedAt() != null
                && alert.getDecisionOutboxEvent() != null
                && alert.getDecisionOutboxStatus() != null;
    }

    private boolean matchesIntent(RegulatedMutationCommandDocument command, AlertDocument alert) {
        if (command.getIntentHash() == null) {
            return true;
        }
        String decision = RegulatedMutationIntentHasher.canonicalValue(alert.getAnalystDecision());
        String reasonHash = RegulatedMutationIntentHasher.hash(alert.getDecisionReason());
        String tagsHash = RegulatedMutationIntentHasher.hash(alert.getDecisionTags());
        String intentHash = RegulatedMutationIntentHasher.hash("resourceId=" + RegulatedMutationIntentHasher.canonicalValue(alert.getAlertId())
                + "|action=" + AuditAction.SUBMIT_ANALYST_DECISION.name()
                + "|actorId=" + RegulatedMutationIntentHasher.canonicalValue(alert.getAnalystId())
                + "|decision=" + decision
                + "|reasonHash=" + reasonHash
                + "|tagsHash=" + tagsHash);
        return Objects.equals(command.getIntentResourceId(), alert.getAlertId())
                && Objects.equals(command.getIntentAction(), AuditAction.SUBMIT_ANALYST_DECISION.name())
                && Objects.equals(command.getIntentActorId(), alert.getAnalystId())
                && Objects.equals(command.getIntentDecision(), decision)
                && Objects.equals(command.getIntentReasonHash(), reasonHash)
                && Objects.equals(command.getIntentTagsHash(), tagsHash)
                && Objects.equals(command.getIntentHash(), intentHash);
    }
}
