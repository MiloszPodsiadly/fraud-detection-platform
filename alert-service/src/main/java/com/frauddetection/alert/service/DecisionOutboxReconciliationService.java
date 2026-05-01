package com.frauddetection.alert.service;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.PostCommitEvidenceIncompleteException;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

@Service
public class DecisionOutboxReconciliationService {

    public enum Resolution {
        PUBLISHED,
        RETRY_REQUESTED
    }

    private final AlertRepository alertRepository;
    private final AuditService auditService;
    private final AuditDegradationService auditDegradationService;
    private final boolean bankModeFailClosed;

    public DecisionOutboxReconciliationService(
            AlertRepository alertRepository,
            AuditService auditService,
            AuditDegradationService auditDegradationService,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed
    ) {
        this.alertRepository = alertRepository;
        this.auditService = auditService;
        this.auditDegradationService = auditDegradationService;
        this.bankModeFailClosed = bankModeFailClosed;
    }

    public List<UnknownConfirmation> listUnknownConfirmations() {
        return alertRepository.findTop100ByDecisionOutboxStatusOrderByDecidedAtAsc(
                        DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN
                )
                .stream()
                .map(UnknownConfirmation::from)
                .toList();
    }

    public UnknownConfirmation resolve(
            String alertId,
            Resolution resolution,
            String reason,
            ResolutionEvidenceReference evidenceReference,
            String actorId
    ) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolution reason is required");
        }
        String normalizedReason = normalizeReason(reason);
        String normalizedActor = normalize(actorId, 120, "unknown");
        AlertDocument alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown outbox event"));
        if (!DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN.equals(alert.getDecisionOutboxStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "outbox event is not in confirmation-unknown state");
        }
        if (resolution == Resolution.PUBLISHED) {
            ResolutionEvidenceReference.requireBrokerEvidence(evidenceReference);
        } else if (bankModeFailClosed) {
            ResolutionEvidenceReference.require(evidenceReference, "resolution evidence is required in bank mode");
        }

        if (bankModeFailClosed && !alert.isDecisionOutboxResolutionPending()) {
            return auditedOutboxResolution(outboxResourceId(alert), normalizedActor, () -> {
                alert.setDecisionOutboxResolutionPending(true);
                alert.setDecisionOutboxResolutionRequestedAt(Instant.now());
                alert.setDecisionOutboxResolutionRequestedBy(normalizedActor);
                alert.setDecisionOutboxResolutionRequestReason(normalizedReason);
                applyEvidence(alert, evidenceReference);
                return UnknownConfirmation.from(alertRepository.save(alert));
            });
        }
        if (bankModeFailClosed && normalizedActor.equals(alert.getDecisionOutboxResolutionRequestedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "resolution approver must differ from requester");
        }

        return auditedOutboxResolution(outboxResourceId(alert), normalizedActor, () -> {
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

            AlertDocument saved = alertRepository.save(alert);
            return UnknownConfirmation.from(saved);
        });
    }

    private UnknownConfirmation auditedOutboxResolution(
            String resourceId,
            String actorId,
            Supplier<UnknownConfirmation> mutation
    ) {
        auditService.audit(
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX,
                resourceId,
                null,
                actorId,
                AuditOutcome.ATTEMPTED,
                null
        );
        UnknownConfirmation result;
        try {
            result = mutation.get();
        } catch (RuntimeException exception) {
            auditService.audit(
                    AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                    AuditResourceType.DECISION_OUTBOX,
                    resourceId,
                    null,
                    actorId,
                    AuditOutcome.FAILED,
                    "RESOLUTION_STATE_UPDATE_FAILED"
            );
            throw exception;
        }
        try {
            auditService.audit(
                    AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                    AuditResourceType.DECISION_OUTBOX,
                    resourceId,
                    null,
                    actorId,
                    AuditOutcome.SUCCESS,
                    null
            );
        } catch (RuntimeException exception) {
            auditDegradationService.recordPostCommitDegraded(
                    AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                    AuditResourceType.DECISION_OUTBOX,
                    resourceId,
                    "POST_COMMIT_AUDIT_DEGRADED"
            );
            throw new PostCommitEvidenceIncompleteException("Resolution committed; audit evidence is incomplete.");
        }
        return result;
    }

    private static String outboxResourceId(AlertDocument document) {
        if (document.getDecisionOutboxEvent() != null && document.getDecisionOutboxEvent().eventId() != null) {
            return document.getDecisionOutboxEvent().eventId();
        }
        return document.getAlertId();
    }

    private static String normalizeReason(String reason) {
        String normalized = reason.trim().replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private static String normalize(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
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

    public record UnknownConfirmation(
            String alertId,
            String eventId,
            String dedupeKey,
            String status,
            Instant decidedAt,
            int attempts,
            Instant lastAttemptAt,
            Instant publishedAt,
            String failureReason,
            boolean resolutionPending,
            Instant resolutionRequestedAt,
            String resolutionRequestedBy,
            String resolutionEvidenceType,
            String resolutionEvidenceReference,
            Instant resolutionEvidenceVerifiedAt,
            String resolutionEvidenceVerifiedBy,
            Instant resolutionApprovedAt,
            String resolutionApprovedBy,
            String resolutionApprovalReason
    ) {
        static UnknownConfirmation from(AlertDocument document) {
            String eventId = null;
            String dedupeKey = null;
            if (document.getDecisionOutboxEvent() != null) {
                eventId = document.getDecisionOutboxEvent().eventId();
                dedupeKey = document.getDecisionOutboxEvent().dedupeKey();
            }
            return new UnknownConfirmation(
                    document.getAlertId(),
                    eventId,
                    dedupeKey,
                    document.getDecisionOutboxStatus(),
                    document.getDecidedAt(),
                    document.getDecisionOutboxAttempts(),
                    document.getDecisionOutboxLastAttemptAt(),
                    document.getDecisionOutboxPublishedAt(),
                    document.getDecisionOutboxFailureReason(),
                    document.isDecisionOutboxResolutionPending(),
                    document.getDecisionOutboxResolutionRequestedAt(),
                    document.getDecisionOutboxResolutionRequestedBy(),
                    document.getDecisionOutboxResolutionEvidenceType(),
                    document.getDecisionOutboxResolutionEvidenceReference(),
                    document.getDecisionOutboxResolutionEvidenceVerifiedAt(),
                    document.getDecisionOutboxResolutionEvidenceVerifiedBy(),
                    document.getDecisionOutboxResolutionApprovedAt(),
                    document.getDecisionOutboxResolutionApprovedBy(),
                    document.getDecisionOutboxResolutionApprovalReason()
            );
        }
    }
}
