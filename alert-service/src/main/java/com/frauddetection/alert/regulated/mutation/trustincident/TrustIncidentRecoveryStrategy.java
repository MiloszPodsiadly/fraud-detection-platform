package com.frauddetection.alert.regulated.mutation.trustincident;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.regulated.RecoveryValidationResult;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryStrategy;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.trust.TrustIncidentDocument;
import com.frauddetection.alert.trust.TrustIncidentRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Component
public class TrustIncidentRecoveryStrategy implements RegulatedMutationRecoveryStrategy {

    private static final String BUSINESS_STATE_NOT_RECONSTRUCTABLE = "BUSINESS_STATE_NOT_RECONSTRUCTABLE";
    private static final String BUSINESS_STATE_INTENT_MISMATCH = "BUSINESS_STATE_INTENT_MISMATCH";

    private final TrustIncidentRepository repository;

    public TrustIncidentRecoveryStrategy(TrustIncidentRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean supports(AuditAction action, AuditResourceType resourceType) {
        return resourceType == AuditResourceType.TRUST_INCIDENT
                && (action == AuditAction.ACK_TRUST_INCIDENT || action == AuditAction.RESOLVE_TRUST_INCIDENT);
    }

    @Override
    public Optional<RegulatedMutationResponseSnapshot> reconstructSnapshot(RegulatedMutationCommandDocument command) {
        return repository.findById(command.getResourceId())
                .filter(document -> validateDocument(command, document).valid())
                .map(document -> RegulatedMutationResponseSnapshot.fromTrustIncident(com.frauddetection.alert.trust.TrustIncidentResponse.from(document)));
    }

    @Override
    public RecoveryValidationResult validateBusinessState(RegulatedMutationCommandDocument command) {
        return repository.findById(command.getResourceId())
                .map(document -> validateDocument(command, document))
                .orElseGet(() -> RecoveryValidationResult.recoveryRequired(BUSINESS_STATE_NOT_RECONSTRUCTABLE));
    }

    private RecoveryValidationResult validateDocument(RegulatedMutationCommandDocument command, TrustIncidentDocument document) {
        boolean matches = Objects.equals(command.getIntentResourceId(), document.getIncidentId())
                && Objects.equals(command.getIntentActorId(), actorFor(command, document))
                && Objects.equals(command.getIntentAction(), command.getAction())
                && document.getStatus() != null
                && Objects.equals(command.getIntentStatus(), document.getStatus().name());
        return matches
                ? RecoveryValidationResult.accepted()
                : RecoveryValidationResult.recoveryRequired(BUSINESS_STATE_INTENT_MISMATCH);
    }

    private String actorFor(RegulatedMutationCommandDocument command, TrustIncidentDocument document) {
        if (AuditAction.ACK_TRUST_INCIDENT.name().equals(command.getAction())) {
            return document.getAcknowledgedBy();
        }
        return document.getResolvedBy();
    }
}
