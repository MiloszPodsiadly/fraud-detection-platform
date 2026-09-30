package com.frauddetection.alert.regulated.mutation.decisionoutbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.RecoveryValidationResult;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryStrategy;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DecisionOutboxRecoveryStrategy implements RegulatedMutationRecoveryStrategy {

    private static final String IMMUTABLE_OPERATION_RESPONSE_EVIDENCE_UNAVAILABLE =
            "IMMUTABLE_OPERATION_RESPONSE_EVIDENCE_UNAVAILABLE";

    public DecisionOutboxRecoveryStrategy(AlertRepository alertRepository) {
        java.util.Objects.requireNonNull(alertRepository, "alertRepository");
    }

    @Override
    public boolean supports(AuditAction action, AuditResourceType resourceType) {
        return action == AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION && resourceType == AuditResourceType.DECISION_OUTBOX;
    }

    @Override
    public Optional<RegulatedMutationResponseSnapshot> reconstructSnapshot(RegulatedMutationCommandDocument command) {
        return Optional.empty();
    }

    @Override
    public RecoveryValidationResult validateBusinessState(RegulatedMutationCommandDocument command) {
        return RecoveryValidationResult.recoveryRequired(IMMUTABLE_OPERATION_RESPONSE_EVIDENCE_UNAVAILABLE);
    }
}
