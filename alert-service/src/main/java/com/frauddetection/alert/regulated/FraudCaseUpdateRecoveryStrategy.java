package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Component
public class FraudCaseUpdateRecoveryStrategy implements RegulatedMutationRecoveryStrategy {

    private static final String BUSINESS_STATE_NOT_RECONSTRUCTABLE = "BUSINESS_STATE_NOT_RECONSTRUCTABLE";
    private static final String BUSINESS_STATE_INTENT_MISMATCH = "BUSINESS_STATE_INTENT_MISMATCH";

    private final FraudCaseRepository fraudCaseRepository;

    public FraudCaseUpdateRecoveryStrategy(FraudCaseRepository fraudCaseRepository) {
        this.fraudCaseRepository = fraudCaseRepository;
    }

    @Override
    public boolean supports(AuditAction action, AuditResourceType resourceType) {
        return action == AuditAction.UPDATE_FRAUD_CASE && resourceType == AuditResourceType.FRAUD_CASE;
    }

    @Override
    public Optional<RegulatedMutationResponseSnapshot> reconstructSnapshot(RegulatedMutationCommandDocument command) {
        return fraudCaseRepository.findById(command.getResourceId())
                .filter(document -> validateDocument(command, document).valid())
                .map(RegulatedMutationResponseSnapshot::fromFraudCase);
    }

    @Override
    public RecoveryValidationResult validateBusinessState(RegulatedMutationCommandDocument command) {
        return fraudCaseRepository.findById(command.getResourceId())
                .map(document -> validateDocument(command, document))
                .orElseGet(() -> RecoveryValidationResult.recoveryRequired(BUSINESS_STATE_NOT_RECONSTRUCTABLE));
    }

    private RecoveryValidationResult validateDocument(RegulatedMutationCommandDocument command, FraudCaseDocument document) {
        if (command.getIntentHash() == null) {
            return RecoveryValidationResult.accepted();
        }
        String status = RegulatedMutationIntentHasher.canonicalValue(document.getStatus());
        String assigneeHash = RegulatedMutationIntentHasher.hash(document.getAnalystId());
        String notesHash = RegulatedMutationIntentHasher.hash(document.getDecisionReason());
        String tagsHash = RegulatedMutationIntentHasher.hash(document.getDecisionTags());
        String payloadHash = RegulatedMutationIntentHasher.hash("status=" + status
                + "|analystId=" + RegulatedMutationIntentHasher.canonicalValue(document.getAnalystId())
                + "|decisionReason=" + RegulatedMutationIntentHasher.canonicalValue(document.getDecisionReason())
                + "|tags=" + RegulatedMutationIntentHasher.canonicalValue(document.getDecisionTags()));
        String intentHash = RegulatedMutationIntentHasher.hash("resourceId=" + RegulatedMutationIntentHasher.canonicalValue(document.getCaseId())
                + "|action=" + AuditAction.UPDATE_FRAUD_CASE.name()
                + "|actorId=" + RegulatedMutationIntentHasher.canonicalValue(document.getAnalystId())
                + "|status=" + status
                + "|assigneeHash=" + assigneeHash
                + "|notesHash=" + notesHash
                + "|tagsHash=" + tagsHash
                + "|payloadHash=" + payloadHash);
        boolean matches = Objects.equals(command.getIntentResourceId(), document.getCaseId())
                && Objects.equals(command.getIntentActorId(), document.getAnalystId())
                && Objects.equals(command.getIntentAction(), AuditAction.UPDATE_FRAUD_CASE.name())
                && Objects.equals(command.getIntentStatus(), status)
                && Objects.equals(command.getIntentAssigneeHash(), assigneeHash)
                && Objects.equals(command.getIntentNotesHash(), notesHash)
                && Objects.equals(command.getIntentTagsHash(), tagsHash)
                && Objects.equals(command.getIntentPayloadHash(), payloadHash)
                && Objects.equals(command.getIntentHash(), intentHash);
        return matches
                ? RecoveryValidationResult.accepted()
                : RecoveryValidationResult.recoveryRequired(BUSINESS_STATE_INTENT_MISMATCH);
    }
}
