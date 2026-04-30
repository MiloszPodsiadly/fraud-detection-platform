package com.frauddetection.alert.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuditDegradationService {

    private static final Logger log = LoggerFactory.getLogger(AuditDegradationService.class);

    private final AuditDegradationEventRepository repository;

    public AuditDegradationService(AuditDegradationEventRepository repository) {
        this.repository = repository;
    }

    public void recordPostCommitDegraded(AuditAction operation, AuditResourceType resourceType, String resourceId, String reason) {
        AuditDegradationEventDocument event = new AuditDegradationEventDocument();
        event.setAuditId(UUID.randomUUID().toString());
        event.setType(AuditDegradationEventDocument.TYPE_POST_COMMIT_DEGRADED);
        event.setOperation(operation == null ? "UNKNOWN" : operation.name());
        event.setResourceType(resourceType == null ? "UNKNOWN" : resourceType.name());
        event.setResourceId(resourceId);
        event.setTimestamp(Instant.now());
        event.setResolved(false);
        event.setReason(normalizeReason(reason));
        try {
            repository.insert(event);
        } catch (DataAccessException exception) {
            log.warn("Audit degradation event persistence failed: reason=AUDIT_DEGRADATION_PERSISTENCE_FAILED");
        }
    }

    public long unresolvedPostCommitDegradedCount() {
        try {
            return repository.countByTypeAndResolved(AuditDegradationEventDocument.TYPE_POST_COMMIT_DEGRADED, false);
        } catch (DataAccessException exception) {
            log.warn("Audit degradation event lookup failed: reason=AUDIT_DEGRADATION_LOOKUP_FAILED");
            return 1L;
        }
    }

    private String normalizeReason(String reason) {
        if ("POST_COMMIT_AUDIT_DEGRADED".equals(reason) || "DECISION_STATUS_UPGRADE_FAILED".equals(reason)) {
            return reason;
        }
        return "POST_COMMIT_AUDIT_DEGRADED";
    }
}
