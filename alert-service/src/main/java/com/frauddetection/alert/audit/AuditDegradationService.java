package com.frauddetection.alert.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AuditDegradationService {

    private static final Logger log = LoggerFactory.getLogger(AuditDegradationService.class);

    private final AuditDegradationEventRepository repository;
    private final AuditService auditService;
    private final boolean bankModeFailClosed;

    public AuditDegradationService(AuditDegradationEventRepository repository) {
        this(repository, null, false);
    }

    public AuditDegradationService(AuditDegradationEventRepository repository, AuditService auditService) {
        this(repository, auditService, false);
    }

    @Autowired
    public AuditDegradationService(
            AuditDegradationEventRepository repository,
            AuditService auditService,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed
    ) {
        this.repository = repository;
        this.auditService = auditService;
        this.bankModeFailClosed = bankModeFailClosed;
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

    public long pendingResolutionCount() {
        try {
            return repository.countByResolutionPending(true);
        } catch (DataAccessException exception) {
            log.warn("Audit degradation pending-resolution lookup failed: reason=AUDIT_DEGRADATION_LOOKUP_FAILED");
            return 1L;
        }
    }

    public List<AuditDegradationEventDocument> unresolvedEvents() {
        return repository.findTop100ByResolvedOrderByTimestampAsc(false);
    }

    public AuditDegradationEventDocument resolveDegradation(
            String auditId,
            String actor,
            String reason,
            ResolutionEvidenceReference evidenceReference
    ) {
        AuditService durableAudit = requiredAuditService();
        String normalizedActor = normalize(actor, 120, "unknown");
        String normalizedReason = normalize(reason, 500, "not specified");
        AuditDegradationEventDocument event = repository.findByAuditId(auditId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown audit degradation event."));

        if (bankModeFailClosed) {
            ResolutionEvidenceReference evidence = ResolutionEvidenceReference.require(
                    evidenceReference,
                    "resolution evidence is required in bank mode"
            );
            if (!event.isResolutionPending()) {
                return auditedResolutionChange(durableAudit, auditId, normalizedActor, () -> {
                    event.setResolutionPending(true);
                    event.setResolutionRequestedAt(Instant.now());
                    event.setResolutionRequestedBy(normalizedActor);
                    event.setResolutionRequestReason(normalizedReason);
                    applyEvidence(event, evidence);
                    return repository.save(event);
                });
            }
            if (normalizedActor.equals(event.getResolutionRequestedBy())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "resolution approver must differ from requester");
            }
        } else if (evidenceReference != null) {
            ResolutionEvidenceReference.require(evidenceReference, "resolution evidence is incomplete");
        }

        return auditedResolutionChange(durableAudit, auditId, normalizedActor, () -> {
            event.setResolved(true);
            event.setResolutionPending(false);
            event.setResolvedAt(Instant.now());
            event.setResolvedBy(normalizedActor);
            event.setResolutionReason(normalizedReason);
            event.setApprovedAt(Instant.now());
            event.setApprovedBy(normalizedActor);
            event.setApprovalReason(normalizedReason);
            if (evidenceReference != null) {
                applyEvidence(event, evidenceReference);
            }
            return repository.save(event);
        });
    }

    private AuditDegradationEventDocument auditedResolutionChange(
            AuditService durableAudit,
            String auditId,
            String actor,
            Supplier<AuditDegradationEventDocument> mutation
    ) {
        durableAudit.audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                auditId,
                null,
                actor,
                AuditOutcome.ATTEMPTED,
                null
        );
        AuditDegradationEventDocument saved;
        try {
            saved = mutation.get();
        } catch (RuntimeException exception) {
            durableAudit.audit(
                    AuditAction.RESOLVE_AUDIT_DEGRADATION,
                    AuditResourceType.AUDIT_DEGRADATION,
                    auditId,
                    null,
                    actor,
                    AuditOutcome.FAILED,
                    "RESOLUTION_STATE_UPDATE_FAILED"
            );
            throw exception;
        }
        try {
            durableAudit.audit(
                    AuditAction.RESOLVE_AUDIT_DEGRADATION,
                    AuditResourceType.AUDIT_DEGRADATION,
                    auditId,
                    null,
                    actor,
                    AuditOutcome.SUCCESS,
                    null
            );
        } catch (RuntimeException exception) {
            recordPostCommitDegraded(
                    AuditAction.RESOLVE_AUDIT_DEGRADATION,
                    AuditResourceType.AUDIT_DEGRADATION,
                    auditId,
                    "POST_COMMIT_AUDIT_DEGRADED"
            );
            throw new PostCommitEvidenceIncompleteException("Resolution committed; audit evidence is incomplete.");
        }
        return saved;
    }

    private AuditService requiredAuditService() {
        if (auditService == null) {
            throw new IllegalStateException("AuditService is required for audit degradation resolution.");
        }
        return auditService;
    }

    private void applyEvidence(AuditDegradationEventDocument event, ResolutionEvidenceReference evidence) {
        event.setResolutionEvidenceType(evidence.type().name());
        event.setResolutionEvidenceReference(evidence.reference());
        event.setResolutionEvidenceVerifiedAt(evidence.verifiedAt());
        event.setResolutionEvidenceVerifiedBy(evidence.verifiedBy());
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
