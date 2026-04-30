package com.frauddetection.alert.service;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class DecisionOutboxReconciliationService {

    public enum Resolution {
        PUBLISHED,
        RETRY_REQUESTED
    }

    private final AlertRepository alertRepository;
    private final AuditService auditService;

    public DecisionOutboxReconciliationService(AlertRepository alertRepository, AuditService auditService) {
        this.alertRepository = alertRepository;
        this.auditService = auditService;
    }

    public List<UnknownConfirmation> listUnknownConfirmations() {
        return alertRepository.findTop100ByDecisionOutboxStatusOrderByDecidedAtAsc(
                        DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN
                )
                .stream()
                .map(UnknownConfirmation::from)
                .toList();
    }

    public UnknownConfirmation resolve(String alertId, Resolution resolution, String reason, String actorId) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolution reason is required");
        }
        AlertDocument alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown outbox event"));
        if (!DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN.equals(alert.getDecisionOutboxStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "outbox event is not in confirmation-unknown state");
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
        alert.setDecisionOutboxLeaseOwner(null);
        alert.setDecisionOutboxLeaseExpiresAt(null);
        alert.setDecisionOutboxLastError(null);
        alert.setDecisionOutboxFailureReason(normalizeReason(reason));

        AlertDocument saved = alertRepository.save(alert);
        auditService.audit(
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX,
                outboxResourceId(saved),
                null,
                actorId
        );
        return UnknownConfirmation.from(saved);
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

    public record UnknownConfirmation(
            String alertId,
            String eventId,
            String dedupeKey,
            String status,
            Instant decidedAt,
            int attempts,
            Instant lastAttemptAt,
            Instant publishedAt,
            String failureReason
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
                    document.getDecisionOutboxFailureReason()
            );
        }
    }
}
