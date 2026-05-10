package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.persistence.FraudCaseAuditEntryDocument;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class FraudCaseAuditService {

    private final FraudCaseAuditRepository auditRepository;

    public FraudCaseAuditService(FraudCaseAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public FraudCaseAuditEntryDocument append(
            String caseId,
            String actorId,
            FraudCaseAuditAction action,
            FraudCaseStatus previousStatus,
            FraudCaseStatus newStatus,
            Map<String, String> details
    ) {
        FraudCaseAuditEntryDocument document = new FraudCaseAuditEntryDocument();
        document.setId(UUID.randomUUID().toString());
        document.setCaseId(caseId);
        document.setActorId(actorId);
        document.setAction(action);
        document.setOccurredAt(Instant.now());
        document.setPreviousStatus(previousStatus);
        document.setNewStatus(newStatus);
        document.setDetails(details == null ? Map.of() : Map.copyOf(details));
        return auditRepository.save(document);
    }
}
