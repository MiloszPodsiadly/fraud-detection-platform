package com.frauddetection.alert.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/decision-outbox/unknown-confirmations")
public class DecisionOutboxReconciliationController {

    private final DecisionOutboxReconciliationService service;
    private final CurrentAnalystUser currentAnalystUser;

    public DecisionOutboxReconciliationController(
            DecisionOutboxReconciliationService service,
            CurrentAnalystUser currentAnalystUser
    ) {
        this.service = service;
        this.currentAnalystUser = currentAnalystUser;
    }

    @GetMapping
    public UnknownConfirmationsResponse listUnknownConfirmations() {
        return new UnknownConfirmationsResponse(
                service.listUnknownConfirmations().stream()
                        .map(UnknownConfirmationResponse::from)
                        .toList()
        );
    }

    @PostMapping("/{alertId}/resolve")
    public UnknownConfirmationResponse resolve(
            @PathVariable String alertId,
            @Valid @RequestBody ResolveOutboxConfirmationRequest request
    ) {
        String actor = currentAnalystUser.get()
                .map(principal -> principal.userId())
                .orElse("unknown");
        return UnknownConfirmationResponse.from(service.resolve(
                alertId,
                request.resolution(),
                request.reason(),
                request.evidenceReference(),
                actor
        ));
    }

    public record ResolveOutboxConfirmationRequest(
            @NotNull
            DecisionOutboxReconciliationService.Resolution resolution,

            @NotBlank
            @Size(max = 500)
            String reason,

            @JsonProperty("evidence_reference")
            @Valid
            ResolutionEvidenceReference evidenceReference
    ) {
    }

    public record UnknownConfirmationsResponse(List<UnknownConfirmationResponse> events) {
    }

    public record UnknownConfirmationResponse(
            @JsonProperty("alert_id")
            String alertId,
            @JsonProperty("event_id")
            String eventId,
            @JsonProperty("dedupe_key")
            String dedupeKey,
            String status,
            @JsonProperty("decided_at")
            Instant decidedAt,
            int attempts,
            @JsonProperty("last_attempt_at")
            Instant lastAttemptAt,
            @JsonProperty("published_at")
            Instant publishedAt,
            @JsonProperty("failure_reason")
            String failureReason,
            @JsonProperty("resolution_pending")
            boolean resolutionPending,
            @JsonProperty("resolution_requested_at")
            Instant resolutionRequestedAt,
            @JsonProperty("resolution_requested_by")
            String resolutionRequestedBy,
            @JsonProperty("resolution_evidence_type")
            String resolutionEvidenceType,
            @JsonProperty("resolution_evidence_reference")
            String resolutionEvidenceReference,
            @JsonProperty("resolution_evidence_verified_at")
            Instant resolutionEvidenceVerifiedAt,
            @JsonProperty("resolution_evidence_verified_by")
            String resolutionEvidenceVerifiedBy,
            @JsonProperty("resolution_approved_at")
            Instant resolutionApprovedAt,
            @JsonProperty("resolution_approved_by")
            String resolutionApprovedBy,
            @JsonProperty("resolution_approval_reason")
            String resolutionApprovalReason
    ) {
        static UnknownConfirmationResponse from(DecisionOutboxReconciliationService.UnknownConfirmation event) {
            return new UnknownConfirmationResponse(
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
    }
}
