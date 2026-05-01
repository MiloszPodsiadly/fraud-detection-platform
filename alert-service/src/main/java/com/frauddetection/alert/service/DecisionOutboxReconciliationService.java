package com.frauddetection.alert.service;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class DecisionOutboxReconciliationService {

    public enum Resolution {
        PUBLISHED,
        RETRY_REQUESTED
    }

    private final AlertRepository alertRepository;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;
    private final boolean bankModeFailClosed;

    public DecisionOutboxReconciliationService(
            AlertRepository alertRepository,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed
    ) {
        this.alertRepository = alertRepository;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
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
            String actorId,
            String idempotencyKey
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

        String resourceId = outboxResourceId(alert);
        RegulatedMutationCommand<UnknownConfirmation, UnknownConfirmation> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                normalizedActor,
                resourceId,
                AuditResourceType.DECISION_OUTBOX,
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                alert.getCorrelationId(),
                requestHash(alertId, resolution, normalizedReason, evidenceReference, normalizedActor),
                () -> applyResolution(alertId, resolution, normalizedReason, evidenceReference, normalizedActor),
                (result, state) -> result,
                DecisionOutboxReconciliationService::snapshot,
                DecisionOutboxReconciliationService::restore,
                state -> statusResponse(alert, state)
        );
        return regulatedMutationCoordinator.commit(command).response();
    }

    private UnknownConfirmation applyResolution(
            String alertId,
            Resolution resolution,
            String normalizedReason,
            ResolutionEvidenceReference evidenceReference,
            String normalizedActor
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

    private UnknownConfirmation statusResponse(AlertDocument alert, RegulatedMutationState state) {
        UnknownConfirmation current = UnknownConfirmation.from(alert);
        return new UnknownConfirmation(
                current.alertId(),
                current.eventId(),
                current.dedupeKey(),
                state.name(),
                current.decidedAt(),
                current.attempts(),
                current.lastAttemptAt(),
                current.publishedAt(),
                current.failureReason(),
                current.resolutionPending(),
                current.resolutionRequestedAt(),
                current.resolutionRequestedBy(),
                current.resolutionEvidenceType(),
                current.resolutionEvidenceReference(),
                current.resolutionEvidenceVerifiedAt(),
                current.resolutionEvidenceVerifiedBy(),
                current.resolutionApprovedAt(),
                current.resolutionApprovedBy(),
                current.resolutionApprovalReason()
        );
    }

    private static RegulatedMutationResponseSnapshot snapshot(UnknownConfirmation event) {
        return new RegulatedMutationResponseSnapshot(
                null,
                null,
                null,
                null,
                null,
                null,
                event.alertId(),
                event.eventId(),
                event.dedupeKey(),
                event.status(),
                event.decidedAt(),
                event.attempts(),
                event.lastAttemptAt(),
                event.publishedAt(),
                event.failureReason(),
                event.resolutionPending(),
                event.resolutionRequestedAt(),
                event.resolutionRequestedBy(),
                event.resolutionEvidenceType(),
                event.resolutionEvidenceReference(),
                event.resolutionEvidenceVerifiedAt(),
                event.resolutionEvidenceVerifiedBy(),
                event.resolutionApprovedAt(),
                event.resolutionApprovedBy(),
                event.resolutionApprovalReason()
        );
    }

    private static UnknownConfirmation restore(RegulatedMutationResponseSnapshot snapshot) {
        return new UnknownConfirmation(
                snapshot.outboxAlertId(),
                snapshot.outboxEventId(),
                snapshot.outboxDedupeKey(),
                snapshot.outboxStatus(),
                snapshot.outboxDecidedAt(),
                snapshot.outboxAttempts() == null ? 0 : snapshot.outboxAttempts(),
                snapshot.outboxLastAttemptAt(),
                snapshot.outboxPublishedAt(),
                snapshot.outboxFailureReason(),
                Boolean.TRUE.equals(snapshot.outboxResolutionPending()),
                snapshot.outboxResolutionRequestedAt(),
                snapshot.outboxResolutionRequestedBy(),
                snapshot.outboxResolutionEvidenceType(),
                snapshot.outboxResolutionEvidenceReference(),
                snapshot.outboxResolutionEvidenceVerifiedAt(),
                snapshot.outboxResolutionEvidenceVerifiedBy(),
                snapshot.outboxResolutionApprovedAt(),
                snapshot.outboxResolutionApprovedBy(),
                snapshot.outboxResolutionApprovalReason()
        );
    }

    private String requestHash(
            String alertId,
            Resolution resolution,
            String reason,
            ResolutionEvidenceReference evidenceReference,
            String actorId
    ) {
        String canonical = "alertId=" + normalize(alertId, 160, "")
                + "|resolution=" + resolution
                + "|reason=" + reason
                + "|actorId=" + actorId
                + "|evidenceType=" + (evidenceReference == null ? "null" : evidenceReference.type())
                + "|evidenceReference=" + (evidenceReference == null ? "null" : evidenceReference.reference())
                + "|evidenceVerifiedAt=" + (evidenceReference == null ? "null" : evidenceReference.verifiedAt())
                + "|evidenceVerifiedBy=" + (evidenceReference == null ? "null" : evidenceReference.verifiedBy());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
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
