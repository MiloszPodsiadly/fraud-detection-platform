package com.frauddetection.alert.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AuditDegradationService {

    private static final Logger log = LoggerFactory.getLogger(AuditDegradationService.class);

    private final AuditDegradationEventRepository repository;
    private final AuditService auditService;

    public AuditDegradationService(AuditDegradationEventRepository repository) {
        this(repository, null);
    }

    @Autowired
    public AuditDegradationService(AuditDegradationEventRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
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

    public long resolvedCount() {
        try {
            return repository.countByResolved(true);
        } catch (DataAccessException exception) {
            log.warn("Audit degradation resolved-count lookup failed: reason=AUDIT_DEGRADATION_LOOKUP_FAILED");
            return 0L;
        }
    }

    public List<AuditDegradationEventDocument> unresolvedEvents() {
        return repository.findTop100ByResolvedOrderByTimestampAsc(false);
    }

    public AuditDegradationEventDocument resolveDegradation(
            String auditId,
            String actor,
            String reason,
            String evidenceReference
    ) {
        AuditDegradationEventDocument event = repository.findByAuditId(auditId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown audit degradation event."));
        event.setResolved(true);
        event.setResolvedAt(Instant.now());
        event.setResolvedBy(normalize(actor, 120, "unknown"));
        event.setResolutionReason(normalize(reason, 500, "not specified"));
        event.setResolutionEvidenceReference(normalize(evidenceReference, 500, null));
        AuditDegradationEventDocument saved = repository.save(event);
        if (auditService != null) {
            auditService.audit(
                    AuditAction.RESOLVE_AUDIT_DEGRADATION,
                    AuditResourceType.AUDIT_DEGRADATION,
                    auditId,
                    null,
                    actor
            );
        }
        return saved;
    }

    private String normalizeReason(String reason) {
        if ("POST_COMMIT_AUDIT_DEGRADED".equals(reason) || "DECISION_STATUS_UPGRADE_FAILED".equals(reason)) {
            return reason;
        }
        return "POST_COMMIT_AUDIT_DEGRADED";
    }

    private String normalize(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
