package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.outbox.OutboxRecordResponse;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;

import java.time.Instant;

public record RegulatedMutationResponseSnapshot(
        @JsonProperty("alert_id")
        String alertId,
        AnalystDecision decision,
        @JsonProperty("resulting_status")
        AlertStatus resultingStatus,
        @JsonProperty("decision_event_id")
        String decisionEventId,
        @JsonProperty("decided_at")
        Instant decidedAt,
        @JsonProperty("operation_status")
        SubmitDecisionOperationStatus operationStatus,
        @JsonProperty("outbox_alert_id")
        String outboxAlertId,
        @JsonProperty("outbox_event_id")
        String outboxEventId,
        @JsonProperty("outbox_dedupe_key")
        String outboxDedupeKey,
        @JsonProperty("outbox_status")
        String outboxStatus,
        @JsonProperty("outbox_decided_at")
        Instant outboxDecidedAt,
        @JsonProperty("outbox_attempts")
        Integer outboxAttempts,
        @JsonProperty("outbox_last_attempt_at")
        Instant outboxLastAttemptAt,
        @JsonProperty("outbox_published_at")
        Instant outboxPublishedAt,
        @JsonProperty("outbox_failure_reason")
        String outboxFailureReason,
        @JsonProperty("outbox_resolution_pending")
        Boolean outboxResolutionPending,
        @JsonProperty("outbox_resolution_requested_at")
        Instant outboxResolutionRequestedAt,
        @JsonProperty("outbox_resolution_requested_by")
        String outboxResolutionRequestedBy,
        @JsonProperty("outbox_resolution_evidence_type")
        String outboxResolutionEvidenceType,
        @JsonProperty("outbox_resolution_evidence_reference")
        String outboxResolutionEvidenceReference,
        @JsonProperty("outbox_resolution_evidence_verified_at")
        Instant outboxResolutionEvidenceVerifiedAt,
        @JsonProperty("outbox_resolution_evidence_verified_by")
        String outboxResolutionEvidenceVerifiedBy,
        @JsonProperty("outbox_resolution_approved_at")
        Instant outboxResolutionApprovedAt,
        @JsonProperty("outbox_resolution_approved_by")
        String outboxResolutionApprovedBy,
        @JsonProperty("outbox_resolution_approval_reason")
        String outboxResolutionApprovalReason
) {
    public RegulatedMutationResponseSnapshot(
            String alertId,
            AnalystDecision decision,
            AlertStatus resultingStatus,
            String decisionEventId,
            Instant decidedAt,
            SubmitDecisionOperationStatus operationStatus
    ) {
        this(
                alertId,
                decision,
                resultingStatus,
                decisionEventId,
                decidedAt,
                operationStatus,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
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

    public static RegulatedMutationResponseSnapshot from(SubmitAnalystDecisionResponse response) {
        return new RegulatedMutationResponseSnapshot(
                response.alertId(),
                response.decision(),
                response.resultingStatus(),
                response.decisionEventId(),
                response.decidedAt(),
                response.operationStatus()
        );
    }

    public SubmitAnalystDecisionResponse toSubmitDecisionResponse() {
        return new SubmitAnalystDecisionResponse(
                alertId,
                decision,
                resultingStatus,
                decisionEventId,
                decidedAt,
                operationStatus
        );
    }

    public static RegulatedMutationResponseSnapshot from(OutboxRecordResponse response) {
        return new RegulatedMutationResponseSnapshot(
                null,
                null,
                null,
                null,
                null,
                null,
                response.resourceId(),
                response.eventId(),
                response.dedupeKey(),
                response.status(),
                null,
                response.attempts(),
                null,
                response.publishedAt(),
                response.lastError(),
                null,
                response.confirmationUnknownAt(),
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

    public OutboxRecordResponse toOutboxRecordResponse() {
        return new OutboxRecordResponse(
                outboxEventId,
                outboxDedupeKey,
                null,
                null,
                outboxAlertId,
                null,
                null,
                outboxStatus,
                outboxAttempts == null ? 0 : outboxAttempts,
                outboxFailureReason,
                outboxPublishedAt,
                outboxResolutionRequestedAt,
                null
        );
    }
}
