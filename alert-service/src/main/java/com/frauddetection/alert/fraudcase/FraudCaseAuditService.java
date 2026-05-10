package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.persistence.FraudCaseAuditEntryDocument;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class FraudCaseAuditService {

    private static final int MAX_DETAIL_VALUE_LENGTH = 256;

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
        document.setDetails(safeDetails(details));
        return auditRepository.save(document);
    }

    private Map<String, String> safeDetails(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (isForbiddenDetailKey(key)) {
                return;
            }
            sanitized.put(key, truncate(value));
        });
        return Map.copyOf(sanitized);
    }

    private boolean isForbiddenDetailKey(String key) {
        if (key == null) {
            return true;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("stack")
                || normalized.contains("exception")
                || normalized.contains("idempotency")
                || normalized.contains("requesthash")
                || normalized.contains("request_hash")
                || normalized.contains("payloadhash")
                || normalized.contains("payload_hash")
                || normalized.contains("leaseowner")
                || normalized.contains("lease_owner");
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_DETAIL_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_DETAIL_VALUE_LENGTH);
    }
}
