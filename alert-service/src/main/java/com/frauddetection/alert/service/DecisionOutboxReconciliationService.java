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
import com.frauddetection.alert.regulated.mutation.decisionoutbox.DecisionOutboxReconciliationMutationHandler;
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
    private final DecisionOutboxReconciliationMutationHandler mutationHandler;
    private final boolean bankModeFailClosed;

    public DecisionOutboxReconciliationService(
            AlertRepository alertRepository,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            DecisionOutboxReconciliationMutationHandler mutationHandler,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed
    ) {
        this.alertRepository = alertRepository;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
        this.mutationHandler = mutationHandler;
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
        if (resolution == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolution is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolution reason is required");
        }
        String normalizedReason = normalizeReason(reason);
        String normalizedActor = normalize(actorId, 120, "unknown");
        if (resolution == Resolution.PUBLISHED) {
            ResolutionEvidenceReference.requireBrokerEvidence(evidenceReference);
        } else if (bankModeFailClosed) {
            ResolutionEvidenceReference.require(evidenceReference, "resolution evidence is required in bank mode");
        }

        String normalizedAlertId = normalize(alertId, 160, "");
        RegulatedMutationCommand<UnknownConfirmation, UnknownConfirmation> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                normalizedActor,
                normalizedAlertId,
                AuditResourceType.DECISION_OUTBOX,
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                null,
                requestHash(normalizedAlertId, resolution, normalizedReason, evidenceReference, normalizedActor),
                context -> mutationHandler.applyResolution(
                        normalizedAlertId,
                        resolution,
                        normalizedReason,
                        evidenceReference,
                        normalizedActor,
                        bankModeFailClosed
                ),
                (result, state) -> result,
                DecisionOutboxReconciliationService::snapshot,
                DecisionOutboxReconciliationService::restore,
                state -> statusResponse(normalizedAlertId, state)
        );
        return regulatedMutationCoordinator.commit(command).response();
    }

    private UnknownConfirmation statusResponse(String alertId, RegulatedMutationState state) {
        return new UnknownConfirmation(
                alertId,
                null,
                null,
                state.name(),
                null,
                0,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
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
        public static UnknownConfirmation from(AlertDocument document) {
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
