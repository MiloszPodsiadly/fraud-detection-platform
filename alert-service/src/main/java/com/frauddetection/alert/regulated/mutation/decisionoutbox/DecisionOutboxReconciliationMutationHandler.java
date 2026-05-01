package com.frauddetection.alert.regulated.mutation.decisionoutbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.service.DecisionOutboxReconciliationService.Resolution;
import com.frauddetection.alert.service.DecisionOutboxReconciliationService.UnknownConfirmation;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Component
public class DecisionOutboxReconciliationMutationHandler {

    private final AlertRepository alertRepository;

    public DecisionOutboxReconciliationMutationHandler(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    public UnknownConfirmation applyResolution(
            String alertId,
            Resolution resolution,
            String normalizedReason,
            ResolutionEvidenceReference evidenceReference,
            String normalizedActor,
            boolean bankModeFailClosed
    ) {
        AlertDocument alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown outbox event"));
        if (!DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN.equals(alert.getDecisionOutboxStatus())
                && !alert.isDecisionOutboxResolutionPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "outbox event is not in confirmation-unknown state");
        }
        if (bankModeFailClosed && !alert.isDecisionOutboxResolutionPending()) {
            alert.setDecisionOutboxResolutionPending(true);
            alert.setDecisionOutboxResolutionRequestedAt(Instant.now());
            alert.setDecisionOutboxResolutionRequestedBy(normalizedActor);
            alert.setDecisionOutboxResolutionRequestReason(normalizedReason);
            applyEvidence(alert, evidenceReference);
            return UnknownConfirmation.from(alertRepository.save(alert));
        }
        if (bankModeFailClosed && normalizedActor.equals(alert.getDecisionOutboxResolutionRequestedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "resolution approver must differ from requester");
        }
        if (resolution == Resolution.PUBLISHED) {
            alert.setDecisionOutboxStatus(DecisionOutboxStatus.PUBLISHED);
            alert.setDecisionOutboxPublishedAt(Instant.now());
        } else if (resolution == Resolution.RETRY_REQUESTED) {
            alert.setDecisionOutboxStatus(DecisionOutboxStatus.PENDING);
            alert.setDecisionOutboxPublishedAt(null);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported resolution");
        }
        alert.setDecisionOutboxResolutionPending(false);
        alert.setDecisionOutboxResolutionApprovedAt(Instant.now());
        alert.setDecisionOutboxResolutionApprovedBy(normalizedActor);
        alert.setDecisionOutboxResolutionApprovalReason(normalizedReason);
        if (evidenceReference != null) {
            applyEvidence(alert, evidenceReference);
        }
        alert.setDecisionOutboxLeaseOwner(null);
        alert.setDecisionOutboxLeaseExpiresAt(null);
        alert.setDecisionOutboxLastError(null);
        alert.setDecisionOutboxFailureReason(normalizedReason);
        return UnknownConfirmation.from(alertRepository.save(alert));
    }

    private static void applyEvidence(AlertDocument alert, ResolutionEvidenceReference evidence) {
        if (evidence == null) {
            return;
        }
        alert.setDecisionOutboxResolutionEvidenceType(evidence.type().name());
        alert.setDecisionOutboxResolutionEvidenceReference(evidence.reference());
        alert.setDecisionOutboxResolutionEvidenceVerifiedAt(evidence.verifiedAt());
        alert.setDecisionOutboxResolutionEvidenceVerifiedBy(evidence.verifiedBy());
    }
}
