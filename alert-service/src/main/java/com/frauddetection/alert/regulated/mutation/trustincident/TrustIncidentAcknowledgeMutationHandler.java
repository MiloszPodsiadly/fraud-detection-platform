package com.frauddetection.alert.regulated.mutation.trustincident;

import com.frauddetection.alert.trust.TrustIncidentAcknowledgementRequest;
import com.frauddetection.alert.trust.TrustIncidentDocument;
import com.frauddetection.alert.trust.TrustIncidentPolicy;
import com.frauddetection.alert.trust.TrustIncidentRepository;
import com.frauddetection.alert.trust.TrustIncidentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Component
public class TrustIncidentAcknowledgeMutationHandler {

    private final TrustIncidentRepository repository;
    private final TrustIncidentPolicy policy;
    private final boolean bankModeFailClosed;

    public TrustIncidentAcknowledgeMutationHandler(
            TrustIncidentRepository repository,
            TrustIncidentPolicy policy,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed
    ) {
        this.repository = repository;
        this.policy = policy;
        this.bankModeFailClosed = bankModeFailClosed;
    }

    public TrustIncidentDocument acknowledge(
            String incidentId,
            TrustIncidentAcknowledgementRequest request,
            String actorId
    ) {
        if (bankModeFailClosed && (request == null || request.reason() == null || request.reason().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "acknowledgement reason is required in bank mode");
        }
        TrustIncidentDocument incident = loadOpen(incidentId);
        TrustIncidentStatus previousStatus = incident.getStatus();
        String previousAcknowledgedBy = incident.getAcknowledgedBy();
        Instant previousAcknowledgedAt = incident.getAcknowledgedAt();
        String previousAcknowledgementReason = incident.getAcknowledgementReason();
        Instant previousUpdatedAt = incident.getUpdatedAt();
        Instant now = Instant.now();
        incident.setStatus(TrustIncidentStatus.ACKNOWLEDGED);
        incident.setAcknowledgedBy(actorId);
        incident.setAcknowledgedAt(now);
        incident.setAcknowledgementReason(request == null ? null : request.reason());
        incident.setUpdatedAt(now);
        try {
            return repository.save(incident);
        } catch (RuntimeException exception) {
            incident.setStatus(previousStatus);
            incident.setAcknowledgedBy(previousAcknowledgedBy);
            incident.setAcknowledgedAt(previousAcknowledgedAt);
            incident.setAcknowledgementReason(previousAcknowledgementReason);
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
