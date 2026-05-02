package com.frauddetection.alert.regulated.mutation.trustincident;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.trust.TrustIncidentDocument;
import com.frauddetection.alert.trust.TrustIncidentPolicy;
import com.frauddetection.alert.trust.TrustIncidentRepository;
import com.frauddetection.alert.trust.TrustIncidentResolutionRequest;
import com.frauddetection.alert.trust.TrustIncidentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Component
public class TrustIncidentResolveMutationHandler {

    private final TrustIncidentRepository repository;
    private final TrustIncidentPolicy policy;

    public TrustIncidentResolveMutationHandler(TrustIncidentRepository repository, TrustIncidentPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    public TrustIncidentDocument resolve(String incidentId, TrustIncidentResolutionRequest request, String actorId) {
        TrustIncidentDocument incident = loadOpen(incidentId);
        ResolutionEvidenceReference.require(request.resolutionEvidence(), "resolution evidence is required");
        TrustIncidentStatus previousStatus = incident.getStatus();
        String previousActiveDedupeKey = incident.getActiveDedupeKey();
        String previousResolvedBy = incident.getResolvedBy();
        Instant previousResolvedAt = incident.getResolvedAt();
        String previousResolutionReason = incident.getResolutionReason();
        ResolutionEvidenceReference previousResolutionEvidence = incident.getResolutionEvidence();
        Instant previousUpdatedAt = incident.getUpdatedAt();
        Instant now = Instant.now();
        incident.setStatus(request.falsePositive() ? TrustIncidentStatus.FALSE_POSITIVE : TrustIncidentStatus.RESOLVED);
        incident.setActiveDedupeKey(null);
        incident.setResolvedBy(actorId);
        incident.setResolvedAt(now);
        incident.setResolutionReason(request.reason());
        incident.setResolutionEvidence(request.resolutionEvidence());
        incident.setUpdatedAt(now);
        try {
            return repository.save(incident);
        } catch (RuntimeException exception) {
            incident.setStatus(previousStatus);
            incident.setActiveDedupeKey(previousActiveDedupeKey);
            incident.setResolvedBy(previousResolvedBy);
            incident.setResolvedAt(previousResolvedAt);
            incident.setResolutionReason(previousResolutionReason);
            incident.setResolutionEvidence(previousResolutionEvidence);
            incident.setUpdatedAt(previousUpdatedAt);
            throw exception;
        }
    }

    private TrustIncidentDocument loadOpen(String incidentId) {
        TrustIncidentDocument incident = repository.findById(incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "trust incident not found"));
        if (!policy.openStatuses().contains(incident.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "trust incident is not open");
        }
        return incident;
    }
}
