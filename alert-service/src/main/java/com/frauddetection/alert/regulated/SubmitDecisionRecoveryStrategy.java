package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
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
    private final TransactionalOutboxRecordRepository outboxRepository;

    public SubmitDecisionRecoveryStrategy(
            AlertRepository alertRepository,
            TransactionalOutboxRecordRepository outboxRepository
    ) {
        this.alertRepository = alertRepository;
        this.outboxRepository = outboxRepository;
    }

    @Override
    public boolean supports(AuditAction action, AuditResourceType resourceType) {
        return action == AuditAction.SUBMIT_ANALYST_DECISION && resourceType == AuditResourceType.ALERT;
    }

    @Override
    public Optional<RegulatedMutationResponseSnapshot> reconstructSnapshot(RegulatedMutationCommandDocument command) {
        Optional<TransactionalOutboxRecordDocument> outbox = authoritativeOutbox(command);
        if (outbox.isEmpty()) {
            return Optional.empty();
        }
        return alertRepository.findById(command.getResourceId())
                .filter(this::hasCommittedDecision)
                .filter(alert -> Objects.equals(alert.getDecisionOutboxEventId(), outbox.get().getEventId()))
                .map(alert -> new RegulatedMutationResponseSnapshot(
                        alert.getAlertId(),
                        alert.getAnalystDecision(),
                        alert.getAlertStatus() == null ? AlertStatus.RESOLVED : alert.getAlertStatus(),
                        outbox.get().getEventId(),
                        alert.getDecidedAt(),
                        SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                ));
    }

    @Override
    public RecoveryValidationResult validateBusinessState(RegulatedMutationCommandDocument command) {
        if (authoritativeOutbox(command).isEmpty()) {
            return RecoveryValidationResult.recoveryRequired(BUSINESS_STATE_NOT_RECONSTRUCTABLE);
        }
        Optional<AlertDocument> alert = alertRepository.findById(command.getResourceId())
                .filter(this::hasCommittedDecision)
                .filter(document -> Objects.equals(document.getDecisionOutboxEventId(), command.getOutboxEventId()));
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
                && alert.getDecisionOutboxEventId() != null
                && alert.getDecisionOutboxStatus() != null;
    }

    private Optional<TransactionalOutboxRecordDocument> authoritativeOutbox(RegulatedMutationCommandDocument command) {
        return outboxRepository.findByMutationCommandId(command.getId())
                .filter(outbox -> Objects.equals(outbox.getMutationCommandId(), command.getId()))
                .filter(outbox -> Objects.equals(outbox.getResourceId(), command.getResourceId()))
                .filter(outbox -> Objects.equals(outbox.getEventId(), command.getOutboxEventId()));
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
